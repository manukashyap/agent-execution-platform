package com.conversive.aep.engine.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.engine.EngineHarness;
import com.conversive.aep.engine.workflow.DagInterpreterWorkflow;
import com.conversive.aep.execution.ExecutionStatus;
import com.conversive.aep.execution.persistence.ExecutionRecord;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.service.ExecutionService;
import com.conversive.aep.support.Fixtures;
import com.conversive.aep.support.InProcessTemporal;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Rows stranded non-terminal (e.g. a Postgres outage during the terminal CAS) follow their closed Temporal run. */
@Import(InProcessTemporal.class)
class ExecutionReconcilerIT extends PostgresIntegrationTest {

    private static final String PDF_WORKFLOW = "lead_enrichment";

    @Autowired
    DefinitionService definitions;
    @Autowired
    ExecutionService executions;
    @Autowired
    ExecutionRepository repository;
    @Autowired
    WorkflowClient client;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;

    private EngineHarness h;
    private ExecutionReconciler reconciler;

    @BeforeEach
    void setUp() {
        TenantId tenant = new TestTenants(jdbc).create("t_recon_" + UUID.randomUUID().toString().substring(0, 8));
        h = new EngineHarness(tenant, definitions, executions, repository, client, jdbc, mapper);
        reconciler = new ExecutionReconciler(repository, client,
                new ReconcilerProperties(true, Duration.ofSeconds(60), Duration.ZERO, 50), Clock.systemUTC());
    }

    private void strand(ExecutionId id, ExecutionStatus status) {
        jdbc.sql("""
                UPDATE workflow_execution SET status = :s, ended_at = NULL, error_code = NULL, error_message = NULL
                 WHERE tenant_id = :t AND id = :id
                """)
                .param("s", status.name()).param("t", h.tenant().value()).param("id", id.value()).update();
    }

    @Test
    void aCompletedRunStrandedRunningTakesTheStatusTheWorkflowReturned() {
        h.publish(Fixtures.pdfExampleJson());
        ExecutionId id = h.start(PDF_WORKFLOW);
        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        strand(id, ExecutionStatus.RUNNING);

        reconciler.reconcileOnce();

        ExecutionRecord row = h.row(id);
        assertThat(row.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(row.endedAt()).isNotNull();
    }

    @Test
    void aTerminatedRunFailsTheRowWithAClearErrorCode() throws Exception {
        h.publish("""
                {"workflow_id":"hang","version":1,"nodes":[
                  {"id":"slow","type":"mcp","timeout_s":30,"config":{"tool":"crm.get","sleepMs":20000}}]}
                """);
        ExecutionId id = h.start("hang");
        EngineHarness.waitUntil(() -> h.row(id).status() == ExecutionStatus.RUNNING, Duration.ofSeconds(20));
        client.newUntypedWorkflowStub(DagInterpreterWorkflow.workflowId(h.tenant().value(), id.toString()))
                .terminate("test");

        reconciler.reconcileOnce();

        ExecutionRecord row = h.row(id);
        assertThat(row.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(row.errorCode()).isEqualTo(ErrorCodes.ENGINE_RUN_CLOSED);
    }

    @Test
    void aRunningRowWhoseRunIsStillOpenIsLeftAlone() throws Exception {
        h.publish("""
                {"workflow_id":"open","version":1,"nodes":[
                  {"id":"slow","type":"mcp","timeout_s":30,"config":{"tool":"crm.get","sleepMs":3000}}]}
                """);
        ExecutionId id = h.start("open");
        EngineHarness.waitUntil(() -> h.row(id).status() == ExecutionStatus.RUNNING, Duration.ofSeconds(20));

        reconciler.reconcileOnce();

        assertThat(h.row(id).status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    void aRowWithNoEngineRunAtAllIsFailedAsMissing() {
        h.publish(Fixtures.pdfExampleJson());
        UUID raw = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO workflow_execution (id, tenant_id, workflow_id, def_version, mode, status, priority,
                    idempotency_key, input, options, deadline_at, created_at, updated_at)
                VALUES (:id, :t, :wf, 1, 'LIVE', 'RUNNING', 'NORMAL', :k, '{}'::jsonb, '{}'::jsonb,
                    now() + interval '1 hour', now(), now())
                """)
                .param("id", raw).param("t", h.tenant().value()).param("wf", PDF_WORKFLOW)
                .param("k", raw.toString()).update();

        reconciler.reconcileOnce();

        ExecutionRecord row = h.row(new ExecutionId(raw));
        assertThat(row.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(row.errorCode()).isEqualTo(ErrorCodes.ENGINE_RUN_MISSING);
    }

    @Test
    void rowsYoungerThanMinAgeAreNotTouched() {
        ExecutionReconciler patient = new ExecutionReconciler(repository, client,
                new ReconcilerProperties(true, Duration.ofSeconds(60), Duration.ofHours(1), 50), Clock.systemUTC());
        h.publish(Fixtures.pdfExampleJson());
        ExecutionId id = h.start(PDF_WORKFLOW);
        h.await(id);
        strand(id, ExecutionStatus.RUNNING);

        patient.reconcileOnce();

        assertThat(h.row(id).status()).isEqualTo(ExecutionStatus.RUNNING);
    }
}
