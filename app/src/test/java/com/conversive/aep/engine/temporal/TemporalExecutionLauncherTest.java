package com.conversive.aep.engine.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.engine.workflow.ExecutionRequest;
import com.conversive.aep.engine.workflow.WorkflowNames;
import com.conversive.aep.support.Fixtures;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TemporalExecutionLauncherTest {

    private static final TenantId TENANT = new TenantId("t_dev");
    private static final ExecutionId EXEC = ExecutionId.of("00000000-0000-0000-0000-000000000001");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final WorkflowClient client = mock(WorkflowClient.class);
    private final WorkflowStub stub = mock(WorkflowStub.class);
    private final TemporalExecutionLauncher launcher = new TemporalExecutionLauncher(client,
            new TemporalProperties(null, null, "q-test", false),
            new LauncherProperties(3, Duration.ZERO, Duration.ofSeconds(7), Duration.ofMinutes(10)), CLOCK);

    private ExecutionRequest request() {
        return Fixtures.pdfExampleRequest(TENANT, EXEC, CLOCK.millis() + 60_000);
    }

    @Test
    void startsWithDeterministicIdTaskQueueAndUseExistingPolicy() {
        ArgumentCaptor<WorkflowOptions> options = ArgumentCaptor.forClass(WorkflowOptions.class);
        when(client.newUntypedWorkflowStub(eq(WorkflowNames.DAG_INTERPRETER), options.capture())).thenReturn(stub);

        launcher.start(request());

        WorkflowOptions o = options.getValue();
        assertThat(o.getWorkflowId()).isEqualTo("exec:t_dev:" + EXEC);
        assertThat(o.getTaskQueue()).isEqualTo("q-test");
        assertThat(o.getWorkflowIdConflictPolicy())
                .isEqualTo(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING);
        assertThat(o.getWorkflowExecutionTimeout()).isEqualTo(Duration.ofSeconds(60).plusMinutes(10));
        verify(stub).start(any(ExecutionRequest.class));
    }

    @Test
    void alreadyStartedCountsAsSuccess() {
        when(client.newUntypedWorkflowStub(eq(WorkflowNames.DAG_INTERPRETER), any(WorkflowOptions.class)))
                .thenReturn(stub);
        doThrow(new WorkflowExecutionAlreadyStarted(WorkflowExecution.getDefaultInstance(),
                WorkflowNames.DAG_INTERPRETER, null)).when(stub).start(any());

        launcher.start(request());

        verify(stub, times(1)).start(any());
    }

    @Test
    void retriesThreeTimesThenThrowsStartFailedWithRetryAfter() {
        when(client.newUntypedWorkflowStub(eq(WorkflowNames.DAG_INTERPRETER), any(WorkflowOptions.class)))
                .thenReturn(stub);
        doThrow(new IllegalStateException("UNAVAILABLE")).when(stub).start(any());

        assertThatThrownBy(() -> launcher.start(request()))
                .isInstanceOfSatisfying(RetryableError.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCodes.START_FAILED);
                    assertThat(e.nextRetryDelay()).isEqualTo(Duration.ofSeconds(7));
                });
        verify(stub, times(3)).start(any());
    }

    @Test
    void succeedsWhenALaterAttemptWorks() {
        when(client.newUntypedWorkflowStub(eq(WorkflowNames.DAG_INTERPRETER), any(WorkflowOptions.class)))
                .thenReturn(stub);
        when(stub.start(any())).thenThrow(new IllegalStateException("UNAVAILABLE"))
                .thenReturn(WorkflowExecution.getDefaultInstance());

        launcher.start(request());

        verify(stub, times(2)).start(any());
    }

    @Test
    void cancelReportsMissingRun() {
        when(client.newUntypedWorkflowStub("exec:t_dev:" + EXEC)).thenReturn(stub);
        when(stub.describe()).thenThrow(new WorkflowNotFoundException(WorkflowExecution.getDefaultInstance(), null, null));

        assertThat(launcher.cancel(TENANT, EXEC)).isFalse();
    }

    @Test
    void cancelOfAClosedRunSendsNothing() {
        WorkflowExecutionDescription closed = description(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED);
        when(client.newUntypedWorkflowStub("exec:t_dev:" + EXEC)).thenReturn(stub);
        when(stub.describe()).thenReturn(closed);

        assertThat(launcher.cancel(TENANT, EXEC)).isFalse();
        verify(stub, never()).cancel();
    }

    @Test
    void cancelRequestsCancellationOfARunningRun() {
        WorkflowExecutionDescription running = description(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
        when(client.newUntypedWorkflowStub("exec:t_dev:" + EXEC)).thenReturn(stub);
        when(stub.describe()).thenReturn(running);

        assertThat(launcher.cancel(TENANT, EXEC)).isTrue();
        verify(stub).cancel();
    }

    private static WorkflowExecutionDescription description(WorkflowExecutionStatus status) {
        WorkflowExecutionDescription description = mock(WorkflowExecutionDescription.class);
        when(description.getStatus()).thenReturn(status);
        return description;
    }
}
