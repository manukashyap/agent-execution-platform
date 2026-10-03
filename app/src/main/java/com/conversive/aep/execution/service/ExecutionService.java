package com.conversive.aep.execution.service;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.DefinitionFreezer;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.definition.StoredDefinition;
import com.conversive.aep.definition.model.DefinitionCodec;
import com.conversive.aep.definition.model.FrozenDefinition;
import com.conversive.aep.engine.ExecutionLauncher;
import com.conversive.aep.engine.RunAlreadyClosedException;
import com.conversive.aep.engine.workflow.ExecutionRequest;
import com.conversive.aep.execution.ExecutionStatus;
import com.conversive.aep.execution.persistence.ExecutionRecord;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.persistence.NewExecution;
import com.conversive.aep.execution.persistence.NodeRunRecord;
import com.conversive.aep.execution.persistence.StatusUpdate;
import com.conversive.aep.tenancy.AdmissionControl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Execution lifecycle at the API edge (06 §3): idempotency lookup first, then admission, insert
 * {@code QUEUED}, Temporal start; a start that fails after inline retries leaves {@code START_FAILED}.
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);
    private static final Set<ExecutionStatus> LIVE = EnumSet.of(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING);

    private final ExecutionRepository executions;
    private final DefinitionService definitions;
    private final DefinitionFreezer freezer;
    private final AdmissionControl admission;
    private final ExecutionLauncher launcher;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ExecutionService(ExecutionRepository executions, DefinitionService definitions, DefinitionFreezer freezer,
                            AdmissionControl admission, ExecutionLauncher launcher, ObjectMapper mapper,
                            Clock clock) {
        this.executions = executions;
        this.definitions = definitions;
        this.freezer = freezer;
        this.admission = admission;
        this.launcher = launcher;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** @param created false when the idempotency key matched an existing execution */
    public record StartResult(ExecutionRecord execution, boolean created) {
    }

    public StartResult start(TenantId tenantId, StartCommand cmd) {
        var existing = executions.findByIdempotencyKey(tenantId, cmd.idempotencyKey());
        if (existing.isPresent()) {
            ExecutionRecord same = sameWorkflow(existing.get(), cmd);
            return new StartResult(same.status() == ExecutionStatus.START_FAILED ? relaunch(same, cmd) : same, false);
        }
        if (cmd.mode() == ExecutionMode.REPLAY) {
            throw new NonRetryableError(ErrorCodes.NOT_IMPLEMENTED, "REPLAY mode is not available yet");
        }
        admission.admit(tenantId);
        StoredDefinition stored = definitions.resolve(tenantId, cmd.workflowId(), cmd.version());
        FrozenDefinition frozen = freezer.freeze(DefinitionCodec.parse(stored.spec()), stored.sha256(),
                definitions.ceilings(tenantId));
        Instant now = clock.instant();
        NewExecution row = new NewExecution(ExecutionId.random(), tenantId, stored.workflowId(), stored.version(),
                cmd.mode(), cmd.priority(), cmd.idempotencyKey(), input(cmd), options(cmd), now,
                now.plusSeconds(frozen.maxDurationS()));
        if (!executions.insert(row)) {
            // Lost a race with a concurrent POST carrying the same key: answer with the winner.
            ExecutionRecord winner = executions.findByIdempotencyKey(tenantId, cmd.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("idempotency conflict without a row"));
            return new StartResult(sameWorkflow(winner, cmd), false);
        }
        launch(row, frozen, cmd);
        return new StartResult(require(tenantId, row.id()), true);
    }

    public ExecutionRecord get(TenantId tenantId, ExecutionId id) {
        return require(tenantId, id);
    }

    public List<NodeRunRecord> nodes(TenantId tenantId, ExecutionId id) {
        require(tenantId, id);
        return executions.findNodeRuns(tenantId, id);
    }

    /**
     * Requests cancellation. The workflow moves the row to {@code CANCELLED}; when Temporal has no
     * open run (never started or already closed) the row is cancelled here.
     *
     * @throws NonRetryableError {@code NOT_FOUND} (also for other tenants), {@code CONFLICT} when terminal
     */
    public ExecutionRecord cancel(TenantId tenantId, ExecutionId id) {
        ExecutionRecord current = require(tenantId, id);
        if (current.status().isTerminal()) {
            throw new NonRetryableError(ErrorCodes.CONFLICT, "execution is already " + current.status());
        }
        if (!launcher.cancel(tenantId, id)) {
            executions.cas(tenantId, id, LIVE, ExecutionStatus.CANCELLED,
                    StatusUpdate.failure(clock.instant(), ErrorCodes.CANCELLED, "cancelled by request"));
        }
        return require(tenantId, id);
    }

    private void launch(NewExecution row, FrozenDefinition frozen, StartCommand cmd) {
        ExecutionRequest request = new ExecutionRequest(row.tenantId(), row.id(), frozen, row.input(), cmd.mode(),
                cmd.dryRun(), cmd.priority(), row.deadlineAt().toEpochMilli());
        startOrRecord(request);
    }

    /**
     * Idempotent replay of a request whose start failed: re-attempts the Temporal start with the same execution
     * id. The row goes back to QUEUED first and returns to START_FAILED when the start fails again. A run that
     * Temporal did accept despite the error moves a START_FAILED row to RUNNING by itself.
     */
    private ExecutionRecord relaunch(ExecutionRecord row, StartCommand cmd) {
        StoredDefinition stored = definitions.get(row.tenantId(), row.workflowId(), row.defVersion());
        FrozenDefinition frozen = freezer.freeze(DefinitionCodec.parse(stored.spec()), stored.sha256(),
                definitions.ceilings(row.tenantId()));
        if (!executions.cas(row.tenantId(), row.id(), EnumSet.of(ExecutionStatus.START_FAILED),
                ExecutionStatus.QUEUED, StatusUpdate.at(clock.instant()))) {
            return require(row.tenantId(), row.id());
        }
        ExecutionRequest request = new ExecutionRequest(row.tenantId(), row.id(), frozen, row.input(), row.mode(),
                cmd.dryRun(), row.priority(), row.deadlineAt().toEpochMilli());
        startOrRecord(request);
        return require(row.tenantId(), row.id());
    }

    /**
     * Starts the run for a QUEUED row. A failed start moves the row to START_FAILED and rethrows. A run that
     * Temporal accepted earlier and that has since closed is recorded as the row's terminal outcome, because no
     * worker will ever move the row out of QUEUED.
     */
    private void startOrRecord(ExecutionRequest request) {
        TenantId tenant = request.tenantId();
        ExecutionId id = request.executionId();
        try {
            launcher.start(request);
        } catch (RetryableError e) {
            log.warn("execution {} could not be started: {}", id, e.getMessage());
            executions.cas(tenant, id, EnumSet.of(ExecutionStatus.QUEUED), ExecutionStatus.START_FAILED,
                    StatusUpdate.failure(clock.instant(), e.code(), e.getMessage()));
            throw e;
        } catch (RunAlreadyClosedException closed) {
            log.warn("execution {} found its engine run already closed: {}", id, closed.getMessage());
            executions.cas(tenant, id, EnumSet.of(ExecutionStatus.QUEUED), closed.outcome(),
                    StatusUpdate.failure(clock.instant(), ErrorCodes.START_FAILED, closed.getMessage()));
        }
    }

    private ExecutionRecord sameWorkflow(ExecutionRecord existing, StartCommand cmd) {
        if (!existing.workflowId().equals(cmd.workflowId())) {
            throw new NonRetryableError(ErrorCodes.CONFLICT,
                    "Idempotency-Key was already used for another workflow");
        }
        return existing;
    }

    private ExecutionRecord require(TenantId tenantId, ExecutionId id) {
        return executions.findById(tenantId, id).orElseThrow(
                () -> new NonRetryableError(ErrorCodes.NOT_FOUND, "execution " + id + " not found"));
    }

    private static JsonNode input(StartCommand cmd) {
        return cmd.input() == null || cmd.input().isNull() ? JsonNodeFactory.instance.objectNode() : cmd.input();
    }

    private JsonNode options(StartCommand cmd) {
        return mapper.createObjectNode().set("dryRun", mapper.valueToTree(cmd.dryRun()));
    }
}
