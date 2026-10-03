package com.conversive.aep.engine.reconcile;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.engine.temporal.TemporalExecutionLauncher;
import com.conversive.aep.engine.workflow.ExecutionResult;
import com.conversive.aep.execution.ExecutionStatus;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.persistence.ExecutionRepository.StaleExecution;
import com.conversive.aep.execution.persistence.StatusUpdate;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Last line of defence against a stranded {@code workflow_execution} row: if the terminal CAS never landed (for
 * example Postgres was down beyond every retry), the row stays RUNNING/COMPENSATING and holds a concurrency slot
 * until its deadline. This finds such rows whose Temporal run has closed and moves them to the matching terminal
 * status through the tenant-scoped, idempotent {@code cas}. A row whose run is still open is left alone; one whose
 * outcome cannot be determined becomes {@code FAILED} with {@code ENGINE_RUN_CLOSED} / {@code ENGINE_RUN_MISSING}.
 */
@Component
@ConditionalOnProperty(name = "aep.engine.reconciler.enabled", matchIfMissing = true)
public class ExecutionReconciler {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReconciler.class);
    private static final Set<ExecutionStatus> LIVE = Set.of(ExecutionStatus.RUNNING, ExecutionStatus.COMPENSATING);
    private static final long RESULT_TIMEOUT_S = 10;

    private final ExecutionRepository repository;
    private final WorkflowClient client;
    private final ReconcilerProperties props;
    private final Clock clock;

    public ExecutionReconciler(ExecutionRepository repository, WorkflowClient client, ReconcilerProperties props,
                               Clock clock) {
        this.repository = repository;
        this.client = client;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${aep.engine.reconciler.interval:60s}",
            fixedDelayString = "${aep.engine.reconciler.interval:60s}")
    public void scheduled() {
        try {
            reconcileOnce();
        } catch (RuntimeException e) {
            log.warn("execution reconciler run failed; retrying next interval", e);
        }
    }

    /** Inspects one batch of stale live rows; returns how many were moved to a terminal status. */
    public int reconcileOnce() {
        Instant now = clock.instant();
        List<StaleExecution> stale = repository.findStaleLive(now.minus(props.minAge()), props.batchSize());
        int moved = 0;
        for (StaleExecution row : stale) {
            try {
                moved += reconcile(row, now) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("cannot reconcile execution {} of tenant {}: {}", row.id(), row.tenantId(), e.toString());
            }
        }
        if (moved > 0) {
            log.warn("reconciled {} stranded executions to a terminal status", moved);
        }
        return moved;
    }

    private boolean reconcile(StaleExecution row, Instant now) {
        Optional<Outcome> outcome = outcomeOf(row);
        if (outcome.isEmpty()) {
            return false;
        }
        Outcome o = outcome.get();
        return repository.cas(row.tenantId(), row.id(), LIVE, o.status(),
                new StatusUpdate(now, o.errorCode(), o.errorMessage(), null));
    }

    private Optional<Outcome> outcomeOf(StaleExecution row) {
        WorkflowStub stub = client.newUntypedWorkflowStub(TemporalExecutionLauncher.workflowId(row.tenantId(), row.id()));
        WorkflowExecutionStatus status;
        try {
            status = stub.describe().getStatus();
        } catch (WorkflowNotFoundException notFound) {
            return Optional.of(Outcome.failed(ErrorCodes.ENGINE_RUN_MISSING,
                    "no engine run exists for this execution (purged or never started); its outcome is unknown"));
        }
        return switch (status) {
            case WORKFLOW_EXECUTION_STATUS_RUNNING, WORKFLOW_EXECUTION_STATUS_UNSPECIFIED -> Optional.empty();
            case WORKFLOW_EXECUTION_STATUS_COMPLETED -> Optional.of(completed(stub));
            case WORKFLOW_EXECUTION_STATUS_TIMED_OUT ->
                    Optional.of(new Outcome(ExecutionStatus.TIMED_OUT, ErrorCodes.TIMEOUT, "the engine run timed out"));
            case WORKFLOW_EXECUTION_STATUS_CANCELED ->
                    Optional.of(new Outcome(ExecutionStatus.CANCELLED, ErrorCodes.CANCELLED,
                            "the engine run was cancelled"));
            default -> Optional.of(Outcome.failed(ErrorCodes.ENGINE_RUN_CLOSED, "the engine run closed ("
                    + status.name().replace("WORKFLOW_EXECUTION_STATUS_", "") + ") before recording its outcome"));
        };
    }

    private static Outcome completed(WorkflowStub stub) {
        try {
            ExecutionResult result = stub.getResult(RESULT_TIMEOUT_S, TimeUnit.SECONDS, ExecutionResult.class);
            if (result != null && result.status().isTerminal()) {
                return new Outcome(result.status(), result.errorCode(), result.errorMessage());
            }
        } catch (Exception e) {
            log.warn("cannot read the result of a completed run: {}", e.toString());
        }
        return Outcome.failed(ErrorCodes.ENGINE_RUN_CLOSED, "the engine run completed but its result is unreadable");
    }

    private record Outcome(ExecutionStatus status, String errorCode, String errorMessage) {

        static Outcome failed(String code, String message) {
            return new Outcome(ExecutionStatus.FAILED, code, message);
        }
    }
}
