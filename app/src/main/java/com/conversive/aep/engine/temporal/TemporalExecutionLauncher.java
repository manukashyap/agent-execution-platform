package com.conversive.aep.engine.temporal;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.engine.ExecutionLauncher;
import com.conversive.aep.engine.workflow.DagInterpreterWorkflow;
import com.conversive.aep.engine.workflow.ExecutionRequest;
import com.conversive.aep.engine.workflow.WorkflowNames;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Starts one {@code DagInterpreterWorkflow} per execution with id {@code exec:{tenant}:{execId}}.
 * The id plus {@code USE_EXISTING} makes a repeated start attach instead of duplicating; an
 * "already started" answer therefore counts as success.
 */
@Component
public class TemporalExecutionLauncher implements ExecutionLauncher {

    private static final Logger log = LoggerFactory.getLogger(TemporalExecutionLauncher.class);
    private static final Duration MIN_RUN_TIMEOUT = Duration.ofSeconds(1);

    private final WorkflowClient client;
    private final TemporalProperties temporal;
    private final LauncherProperties props;
    private final Clock clock;

    public TemporalExecutionLauncher(WorkflowClient client, TemporalProperties temporal, LauncherProperties props,
                                     Clock clock) {
        this.client = client;
        this.temporal = temporal;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void start(ExecutionRequest request) {
        WorkflowOptions options = options(request);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= props.startAttempts(); attempt++) {
            try {
                client.newUntypedWorkflowStub(WorkflowNames.DAG_INTERPRETER, options).start(request);
                return;
            } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("workflow start attempt {}/{} failed for {}: {}", attempt, props.startAttempts(),
                        options.getWorkflowId(), e.toString());
                pauseBeforeRetry(attempt);
            }
        }
        throw new RetryableError(ErrorCodes.START_FAILED, "the execution engine is unavailable; retry later",
                props.retryAfter(), last);
    }

    @Override
    public boolean cancel(TenantId tenantId, ExecutionId executionId) {
        try {
            WorkflowStub stub = client.newUntypedWorkflowStub(workflowId(tenantId, executionId));
            if (stub.describe().getStatus() != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) {
                return false;
            }
            stub.cancel();
            return true;
        } catch (WorkflowNotFoundException notFound) {
            return false;
        } catch (RuntimeException e) {
            throw new RetryableError(ErrorCodes.UPSTREAM_UNAVAILABLE, "the execution engine is unavailable",
                    props.retryAfter(), e);
        }
    }

    public static String workflowId(TenantId tenantId, ExecutionId executionId) {
        return DagInterpreterWorkflow.workflowId(tenantId.value(), executionId.toString());
    }

    private WorkflowOptions options(ExecutionRequest request) {
        Duration untilDeadline = Duration.ofMillis(request.deadlineEpochMs() - clock.millis());
        Duration runTimeout = (untilDeadline.compareTo(MIN_RUN_TIMEOUT) < 0 ? MIN_RUN_TIMEOUT : untilDeadline)
                .plus(props.runTimeoutSlack());
        return WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId(request.tenantId(), request.executionId()))
                .setTaskQueue(temporal.taskQueue())
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setWorkflowExecutionTimeout(runTimeout)
                .build();
    }

    private void pauseBeforeRetry(int attempt) {
        if (attempt >= props.startAttempts() || props.startBackoff().isZero()) {
            return;
        }
        try {
            Thread.sleep(props.startBackoff().toMillis() * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
