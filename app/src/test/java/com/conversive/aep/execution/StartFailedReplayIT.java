package com.conversive.aep.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.engine.ExecutionLauncher;
import com.conversive.aep.engine.temporal.TemporalExecutionLauncher;
import com.conversive.aep.engine.workflow.DagInterpreterWorkflow;
import com.conversive.aep.engine.workflow.ExecutionRequest;
import com.conversive.aep.engine.workflow.ExecutionResult;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.service.ExecutionService;
import com.conversive.aep.execution.service.StartCommand;
import com.conversive.aep.support.Fixtures;
import com.conversive.aep.support.InProcessTemporal;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.conversive.aep.engine.workflow.WorkflowNames;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/** An Idempotency-Key replay that finds a START_FAILED row re-attempts the start with the same execution id. */
@Import({InProcessTemporal.class, StartFailedReplayIT.FlakyLauncherConfig.class})
class StartFailedReplayIT extends PostgresIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class FlakyLauncherConfig {

        @Bean
        @Primary
        FlakyLauncher flakyLauncher(TemporalExecutionLauncher real) {
            return new FlakyLauncher(real);
        }
    }

    /** Fails the next {@code failures} starts as if Temporal were unreachable. */
    static final class FlakyLauncher implements ExecutionLauncher {

        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger starts = new AtomicInteger();
        volatile ExecutionRequest lastRequest;
        private final ExecutionLauncher real;

        FlakyLauncher(ExecutionLauncher real) {
            this.real = real;
        }

        @Override
        public void start(ExecutionRequest request) {
            starts.incrementAndGet();
            lastRequest = request;
            if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new RetryableError(ErrorCodes.START_FAILED, "temporal unreachable (test)", null);
            }
            real.start(request);
        }

        @Override
        public boolean cancel(TenantId tenantId, ExecutionId executionId) {
            return real.cancel(tenantId, executionId);
        }
    }

    @Autowired
    DefinitionService definitions;
    @Autowired
    ExecutionService executions;
    @Autowired
    ExecutionRepository repository;
    @Autowired
    FlakyLauncher launcher;
    @Autowired
    TemporalExecutionLauncher realLauncher;
    @Autowired
    WorkflowClient client;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;

    @Test
    void replayAfterStartFailedRelaunchesTheSameExecution() throws Exception {
        TenantId tenant = new TestTenants(jdbc).create("t_replay");
        definitions.publish(tenant, mapper.readTree(Fixtures.pdfExampleJson()));
        StartCommand cmd = new StartCommand("lead_enrichment", null, mapper.readTree("{}"), null, null, null,
                "replay-1");

        launcher.starts.set(0);
        launcher.failures.set(2);
        assertThatThrownBy(() -> executions.start(tenant, cmd)).isInstanceOf(RetryableError.class);
        ExecutionId id = repository.findByIdempotencyKey(tenant, "replay-1").orElseThrow().id();
        assertThat(status(tenant, id)).isEqualTo(ExecutionStatus.START_FAILED);

        assertThatThrownBy(() -> executions.start(tenant, cmd))
                .isInstanceOfSatisfying(RetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.START_FAILED));
        assertThat(status(tenant, id)).isEqualTo(ExecutionStatus.START_FAILED);

        ExecutionService.StartResult replay = executions.start(tenant, cmd);
        assertThat(replay.created()).isFalse();
        assertThat(replay.execution().id()).isEqualTo(id);
        assertThat(replay.execution().status()).isEqualTo(ExecutionStatus.QUEUED);
        assertThat(launcher.starts.get()).isEqualTo(3);

        ExecutionResult result = client.newUntypedWorkflowStub(
                DagInterpreterWorkflow.workflowId(tenant.value(), id.toString())).getResult(ExecutionResult.class);
        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(status(tenant, id)).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(executions.start(tenant, cmd).execution().id()).isEqualTo(id);
        assertThat(launcher.starts.get()).isEqualTo(3);
    }

    @Test
    void aRunTemporalStartedWhileTheClientSawAFailureStillRunsAndHealsTheRow() throws Exception {
        TenantId tenant = new TestTenants(jdbc).create("t_heal");
        definitions.publish(tenant, mapper.readTree(Fixtures.pdfExampleJson()));
        StartCommand cmd = new StartCommand("lead_enrichment", null, mapper.readTree("{}"), null, null, null,
                "heal-1");
        launcher.failures.set(1);
        assertThatThrownBy(() -> executions.start(tenant, cmd)).isInstanceOf(RetryableError.class);
        ExecutionId id = repository.findByIdempotencyKey(tenant, "heal-1").orElseThrow().id();
        assertThat(status(tenant, id)).isEqualTo(ExecutionStatus.START_FAILED);

        // Temporal had in fact accepted the start: the run begins while the row says START_FAILED.
        realLauncher.start(launcher.lastRequest);

        ExecutionResult result = client.newUntypedWorkflowStub(
                DagInterpreterWorkflow.workflowId(tenant.value(), id.toString())).getResult(ExecutionResult.class);
        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(status(tenant, id)).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    void replayFindingTheRunAlreadyClosedRecordsItsOutcomeInsteadOfStayingQueued() throws Exception {
        TenantId tenant = new TestTenants(jdbc).create("t_closed");
        definitions.publish(tenant, mapper.readTree(Fixtures.pdfExampleJson()));
        StartCommand cmd = new StartCommand("lead_enrichment", null, mapper.readTree("{}"), null, null, null,
                "closed-1");
        launcher.failures.set(1);
        assertThatThrownBy(() -> executions.start(tenant, cmd)).isInstanceOf(RetryableError.class);
        ExecutionId id = repository.findByIdempotencyKey(tenant, "closed-1").orElseThrow().id();
        // The run Temporal accepted closed without touching the row (here: terminated before any worker ran it).
        WorkflowStub orphan = client.newUntypedWorkflowStub(WorkflowNames.DAG_INTERPRETER,
                WorkflowOptions.newBuilder().setTaskQueue("unpolled")
                        .setWorkflowId(DagInterpreterWorkflow.workflowId(tenant.value(), id.toString())).build());
        orphan.start(launcher.lastRequest);
        orphan.terminate("test: closed before running");

        ExecutionService.StartResult replay = executions.start(tenant, cmd);

        assertThat(replay.execution().id()).isEqualTo(id);
        assertThat(replay.execution().status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(replay.execution().errorCode()).isEqualTo(ErrorCodes.START_FAILED);
    }

    private ExecutionStatus status(TenantId tenant, ExecutionId id) {
        return repository.findById(tenant, id).orElseThrow().status();
    }
}
