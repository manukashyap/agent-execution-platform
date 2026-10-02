package com.conversive.aep.observability.persistence;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.observability.persistence.TraceRows.BudgetRow;
import com.conversive.aep.observability.persistence.TraceRows.ExecutionRow;
import com.conversive.aep.observability.persistence.TraceRows.LlmCallRow;
import com.conversive.aep.observability.persistence.TraceRows.NodeRunRow;
import com.conversive.aep.observability.persistence.TraceRows.SideEffectRow;
import com.conversive.aep.observability.persistence.TraceRows.ToolCallRow;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only queries behind {@code /trace}. Every statement filters on {@code tenant_id}. */
@Repository
public class TraceRepository {

    private final JdbcClient jdbc;

    public TraceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ExecutionRow> execution(TenantId tenant, ExecutionId id) {
        return jdbc.sql("""
                SELECT id, workflow_id, def_version, mode, status, error_code, started_at, ended_at
                  FROM workflow_execution
                 WHERE tenant_id = :t AND id = :e""")
                .param("t", tenant.value()).param("e", id.value())
                .query((rs, n) -> new ExecutionRow(rs.getObject("id", UUID.class), rs.getString("workflow_id"),
                        rs.getInt("def_version"), rs.getString("mode"), rs.getString("status"),
                        rs.getString("error_code"), instant(rs, "started_at"), instant(rs, "ended_at")))
                .optional();
    }

    /** node id → node type, from the definition version the execution ran. */
    public Map<String, String> nodeTypes(TenantId tenant, String workflowId, int version) {
        return jdbc.sql("""
                SELECT n ->> 'id' AS node_id, COALESCE(n ->> 'type', 'unknown') AS node_type
                  FROM workflow_definition d,
                       jsonb_array_elements(CASE WHEN jsonb_typeof(d.spec -> 'nodes') = 'array'
                                                 THEN d.spec -> 'nodes' ELSE '[]'::jsonb END) n
                 WHERE d.tenant_id = :t AND d.workflow_id = :w AND d.def_version = :v
                   AND n ->> 'id' IS NOT NULL""")
                .param("t", tenant.value()).param("w", workflowId).param("v", version)
                .query((rs, n) -> Map.entry(rs.getString("node_id"), rs.getString("node_type")))
                .list().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    public List<NodeRunRow> nodeRuns(TenantId tenant, ExecutionId id) {
        return jdbc.sql("""
                SELECT node_id, call_index, phase, attempt, status, error_code, started_at, ended_at
                  FROM node_run
                 WHERE tenant_id = :t AND execution_id = :e
                 ORDER BY node_id, call_index, phase, attempt""")
                .param("t", tenant.value()).param("e", id.value())
                .query((rs, n) -> new NodeRunRow(rs.getString("node_id"), rs.getInt("call_index"),
                        rs.getString("phase"), rs.getInt("attempt"), rs.getString("status"),
                        rs.getString("error_code"), instant(rs, "started_at"), instant(rs, "ended_at")))
                .list();
    }

    public List<LlmCallRow> llmCalls(TenantId tenant, ExecutionId id) {
        return jdbc.sql("""
                SELECT node_id, call_index, attempt, turn, seq, provider, model, reason, prompt_tokens,
                       completion_tokens, cost_usd, latency_ms, outcome, error_code, created_at
                  FROM llm_call
                 WHERE tenant_id = :t AND execution_id = :e
                 ORDER BY node_id, call_index, attempt, turn, seq, id""")
                .param("t", tenant.value()).param("e", id.value())
                .query((rs, n) -> new LlmCallRow(rs.getString("node_id"), rs.getInt("call_index"),
                        rs.getInt("attempt"), rs.getInt("turn"), rs.getInt("seq"), rs.getString("provider"),
                        rs.getString("model"), rs.getString("reason"), rs.getInt("prompt_tokens"),
                        rs.getInt("completion_tokens"), amount(rs, "cost_usd"), rs.getLong("latency_ms"),
                        rs.getString("outcome"), rs.getString("error_code"), instant(rs, "created_at")))
                .list();
    }

    public List<ToolCallRow> toolCalls(TenantId tenant, ExecutionId id) {
        return jdbc.sql("""
                SELECT node_id, call_index, phase, attempt, tool_name, outcome, error_code, latency_ms, created_at
                  FROM tool_call_audit
                 WHERE tenant_id = :t AND execution_id = :e
                 ORDER BY node_id, call_index, phase, attempt, id""")
                .param("t", tenant.value()).param("e", id.value())
                .query((rs, n) -> new ToolCallRow(rs.getString("node_id"), rs.getInt("call_index"),
                        rs.getString("phase"), rs.getInt("attempt"), rs.getString("tool_name"),
                        rs.getString("outcome"), rs.getString("error_code"), rs.getLong("latency_ms"),
                        instant(rs, "created_at")))
                .list();
    }

    public List<SideEffectRow> sideEffects(TenantId tenant, ExecutionId id) {
        return jdbc.sql("""
                SELECT node_id, call_index, phase, state, idempotency_mode, owner_attempt, external_ref, updated_at
                  FROM side_effect_ledger
                 WHERE tenant_id = :t AND execution_id = :e
                 ORDER BY node_id, call_index, phase""")
                .param("t", tenant.value()).param("e", id.value())
                .query((rs, n) -> new SideEffectRow(rs.getString("node_id"), rs.getInt("call_index"),
                        rs.getString("phase"), rs.getString("state"), rs.getString("idempotency_mode"),
                        rs.getInt("owner_attempt"), rs.getString("external_ref"), instant(rs, "updated_at")))
                .list();
    }

    public BudgetRow budget(TenantId tenant, ExecutionId id) {
        return jdbc.sql("""
                SELECT (SELECT limit_usd FROM execution_budget
                         WHERE tenant_id = :t AND execution_id = :e) AS limit_usd,
                       COALESCE(SUM(amount_usd) FILTER (WHERE status = 'RESERVED'), 0) AS reserved_usd,
                       COALESCE(SUM(actual_usd) FILTER (WHERE status = 'CONFIRMED'), 0) AS confirmed_usd,
                       COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled
                  FROM budget_reservation
                 WHERE tenant_id = :t AND execution_id = :e""")
                .param("t", tenant.value()).param("e", id.value())
                .query((rs, n) -> new BudgetRow(nullableAmount(rs, "limit_usd"), amount(rs, "reserved_usd"),
                        amount(rs, "confirmed_usd"), rs.getInt("cancelled")))
                .single();
    }

    private static BigDecimal amount(ResultSet rs, String column) throws SQLException {
        BigDecimal value = nullableAmount(rs, column);
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Without trailing zeros (0.002, not 0.002000) but never in exponent form. */
    private static BigDecimal nullableAmount(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
