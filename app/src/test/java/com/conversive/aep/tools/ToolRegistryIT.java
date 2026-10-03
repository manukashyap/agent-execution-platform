package com.conversive.aep.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.Reversibility;
import com.conversive.aep.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class ToolRegistryIT extends PostgresIntegrationTest {

    @Autowired
    ToolRegistry registry;

    @Autowired
    JdbcClient jdbc;

    @Test
    void seedsTheSixContractToolsPlusTheCrmInverse() {
        assertThat(registry.findAll()).extracting(ToolDefinition::name).containsExactly(
                "crm.delete", "crm.get", "crm.upsert", "leads.fetch", "messaging.send",
                "payments.charge", "payments.refund");
    }

    @Test
    void chargeIsCompensatableByRefundWithNativeKey() {
        ToolDefinition charge = registry.find("payments.charge").orElseThrow();

        assertThat(charge.reversibility()).isEqualTo(Reversibility.COMPENSATABLE);
        assertThat(charge.idempotency()).isEqualTo(IdempotencyMode.NATIVE_KEY);
        assertThat(charge.compensation()).contains("payments.refund");
        assertThat(charge.timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(charge.scopes()).containsExactly("payments:write");
        assertThat(charge.inputSchema().get("required").toString())
                .isEqualTo("[\"customer_id\",\"amount_cents\",\"currency\"]");
        assertThat(charge.retry().get("max_attempts").asInt()).isEqualTo(5);
    }

    @Test
    void classificationMatchesTheSagaModelForEveryTool() {
        assertThat(registry.find("payments.refund").orElseThrow())
                .returns(Reversibility.RETRIABLE, ToolDefinition::reversibility)
                .returns(IdempotencyMode.NATIVE_KEY, ToolDefinition::idempotency);
        assertThat(registry.find("messaging.send").orElseThrow())
                .returns(Reversibility.PIVOT, ToolDefinition::reversibility)
                .returns(IdempotencyMode.NONE, ToolDefinition::idempotency);
        ToolDefinition upsert = registry.find("crm.upsert").orElseThrow();
        assertThat(upsert.idempotency()).isEqualTo(IdempotencyMode.LOOKUP);
        assertThat(upsert.compensation()).contains("crm.delete");
        assertThat(upsert.lookup().get("tool").asText()).isEqualTo("crm.get");
        assertThat(registry.find("crm.get").orElseThrow().sideEffecting()).isFalse();
        assertThat(registry.find("leads.fetch").orElseThrow().inputSchema().get("required")).isNull();
    }

    @Test
    void unknownToolIsEmpty() {
        assertThat(registry.find("nope.tool")).isEmpty();
    }

    @Test
    void ledgerRejectsNonCanonicalState() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO side_effect_ledger (effect_key, tenant_id, execution_id, node_id, phase, call_index,
                    state, idempotency_mode, owner_attempt, lease_until)
                VALUES (:k, 't_dev', :e, 'n', 'FORWARD', 0, 'DONE', 'NONE', 1, now())
                """).param("k", "a".repeat(64)).param("e", UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void compensatableToolWithoutInverseIsRejected() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO tool_registry (tool_name, description, input_schema, output_schema, timeout_ms, retry,
                    reversibility, idempotency)
                VALUES ('x.y', 'x', '{}', '{}', 1000, '{}', 'COMPENSATABLE', 'NATIVE_KEY')
                """).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
