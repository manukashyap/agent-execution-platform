package com.conversive.aep.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

class CoreSchemaIT extends PostgresIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void definition() {
        jdbc.sql("""
                INSERT INTO workflow_definition (tenant_id, workflow_id, def_version, spec, sha256)
                VALUES ('t_dev', 'schema_it', 1, '{}'::jsonb, 'x') ON CONFLICT DO NOTHING
                """).update();
    }

    @Test
    void flywayAppliedV1AndSeededTenants() {
        assertThat(jdbc.sql("SELECT version FROM flyway_schema_history WHERE success").query(String.class).list())
                .contains("1");
        assertThat(jdbc.sql("SELECT id FROM tenant ORDER BY id").query(String.class).list())
                .contains("t_dev", "t_other"); // other ITs add per-test tenants to the shared container
        assertThat(jdbc.sql("SELECT max_node_executions FROM tenant_limits WHERE tenant_id = 't_dev'")
                .query(Integer.class).single()).isEqualTo(500);
    }

    @Test
    void idempotencyKeyIsUniquePerTenant() {
        insertExecution(UUID.randomUUID(), "QUEUED", "idem-1");

        assertThatThrownBy(() -> insertExecution(UUID.randomUUID(), "QUEUED", "idem-1"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void statusMustBeCanonical() {
        assertThatThrownBy(() -> insertExecution(UUID.randomUUID(), "DONE", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void liveExecutionsUseThePartialIndex() {
        String plan = String.join("\n", jdbc.sql("""
                EXPLAIN SELECT count(*) FROM workflow_execution
                WHERE tenant_id = 't_dev' AND status IN ('QUEUED','RUNNING') AND deadline_at > now()
                """).query(String.class).list());
        jdbc.sql("SET enable_seqscan = off").update();
        String forced = String.join("\n", jdbc.sql("""
                EXPLAIN SELECT count(*) FROM workflow_execution
                WHERE tenant_id = 't_dev' AND status IN ('QUEUED','RUNNING') AND deadline_at > now()
                """).query(String.class).list());
        jdbc.sql("SET enable_seqscan = on").update();

        assertThat(plan).isNotBlank();
        assertThat(forced).contains("workflow_execution_live_idx");
    }

    @Test
    void nodeRunKeyIncludesCallIndexPhaseAndAttempt() {
        UUID exec = UUID.randomUUID();
        insertExecution(exec, "RUNNING", null);
        for (String phase : new String[] {"FORWARD", "COMPENSATE"}) {
            for (int callIndex = 0; callIndex < 2; callIndex++) {
                jdbc.sql("""
                        INSERT INTO node_run (tenant_id, execution_id, node_id, call_index, phase, attempt, status)
                        VALUES ('t_dev', :exec, 'n1', :ci, :phase, 1, 'SUCCEEDED')
                        """).param("exec", exec).param("ci", callIndex).param("phase", phase).update();
            }
        }

        assertThat(jdbc.sql("SELECT count(*) FROM node_run WHERE tenant_id = 't_dev' AND execution_id = :exec")
                .param("exec", exec).query(Long.class).single()).isEqualTo(4);
    }

    private void insertExecution(UUID id, String status, String idempotencyKey) {
        jdbc.sql("""
                INSERT INTO workflow_execution (id, tenant_id, workflow_id, def_version, status, idempotency_key, deadline_at)
                VALUES (:id, 't_dev', 'schema_it', 1, :status, :key, now() + interval '1 hour')
                """).param("id", id).param("status", status).param("key", idempotencyKey).update();
    }
}
