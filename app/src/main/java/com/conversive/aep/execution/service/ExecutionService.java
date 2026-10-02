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
            return new StartResult(sameWorkflow(existing.get(), cmd), false);
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
        try {
            launcher.start(request);
        } catch (RetryableError e) {
            log.warn("execution {} could not be started: {}", row.id(), e.getMessage());
            executions.cas(row.tenantId(), row.id(), EnumSet.of(ExecutionStatus.QUEUED), ExecutionStatus.START_FAILED,
                    StatusUpdate.failure(clock.instant(), e.code(), e.getMessage()));
            throw e;
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
