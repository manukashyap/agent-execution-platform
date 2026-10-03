package com.conversive.aep.tools.persistence;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.tools.ToolCallAudit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcToolCallAudit implements ToolCallAudit {

    private final JdbcClient jdbc;

    public JdbcToolCallAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(Entry e) {
        jdbc.sql("""
                INSERT INTO tool_call_audit (tenant_id, execution_id, node_id, call_index, phase, attempt, tool_name,
                    args_sha256, outcome, error_code, latency_ms, effect_key)
                VALUES (:tenantId, :executionId, :nodeId, :callIndex, :phase, :attempt, :toolName,
                    :argsSha256, :outcome, :errorCode, :latencyMs, :effectKey)
                """)
                .param("tenantId", e.tenantId().value())
                .param("executionId", e.executionId().value())
                .param("nodeId", e.nodeId())
                .param("callIndex", e.callIndex())
                .param("phase", e.phase().name())
                .param("attempt", e.attempt())
                .param("toolName", e.toolName())
                .param("argsSha256", e.argsSha256())
                .param("outcome", e.outcome())
                .param("errorCode", e.errorCode())
                .param("latencyMs", e.latencyMs())
                .param("effectKey", e.effectKey())
                .update();
    }

    @Override
    public List<Entry> findByExecution(TenantId tenantId, ExecutionId executionId) {
        return jdbc.sql("""
                SELECT tenant_id, execution_id, node_id, call_index, phase, attempt, tool_name, args_sha256, outcome,
                       error_code, latency_ms, effect_key
                FROM tool_call_audit WHERE tenant_id = :tenantId AND execution_id = :executionId ORDER BY id
                """)
                .param("tenantId", tenantId.value())
                .param("executionId", executionId.value())
                .query(JdbcToolCallAudit::map)
                .list();
    }

    private static Entry map(ResultSet rs, int row) throws SQLException {
        return new Entry(TenantId.of(rs.getString("tenant_id")), new ExecutionId(rs.getObject("execution_id", UUID.class)),
                rs.getString("node_id"), rs.getInt("call_index"), Phase.valueOf(rs.getString("phase")),
                rs.getInt("attempt"), rs.getString("tool_name"), rs.getString("args_sha256"), rs.getString("outcome"),
                rs.getString("error_code"), rs.getLong("latency_ms"), rs.getString("effect_key"));
    }
}
