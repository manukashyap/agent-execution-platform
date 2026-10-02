package com.conversive.aep.engine.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.engine.workflow.WorkflowNames;
import com.conversive.aep.support.Fixtures;
import com.conversive.aep.tenancy.TemporalPriorityPolicy;
import com.conversive.aep.tenancy.TenantTier;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** T7.3: the workflow is started with the tenant's priority key and fairness key. */
class LauncherPriorityTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void workflowOptionsCarryTheTenantPriorityAndFairnessKey() {
        WorkflowClient client = mock(WorkflowClient.class);
        ArgumentCaptor<WorkflowOptions> options = ArgumentCaptor.forClass(WorkflowOptions.class);
        when(client.newUntypedWorkflowStub(eq(WorkflowNames.DAG_INTERPRETER), options.capture()))
                .thenReturn(mock(WorkflowStub.class));
        TemporalExecutionLauncher launcher = new TemporalExecutionLauncher(client,
                new TemporalProperties(null, null, "q-test", false), LauncherProperties.defaults(), CLOCK,
                new TemporalPriorityPolicy(tenant -> TenantTier.ENTERPRISE));

        launcher.start(Fixtures.pdfExampleRequest(TenantId.of("t_ent"), ExecutionId.random(), CLOCK.millis() + 60_000));

        io.temporal.common.Priority priority = options.getValue().getPriority();
        assertThat(priority.getPriorityKey()).isEqualTo(2);
        assertThat(priority.getFairnessKey()).isEqualTo("t_ent");
        assertThat(priority.getFairnessWeight()).isEqualTo(4f);
    }
}
