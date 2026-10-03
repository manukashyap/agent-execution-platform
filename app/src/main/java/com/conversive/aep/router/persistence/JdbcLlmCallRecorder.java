package com.conversive.aep.router.persistence;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.router.Candidate;
import com.conversive.aep.router.LlmCallRecord;
import com.conversive.aep.router.LlmCallRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class JdbcLlmCallRecorder implements LlmCallRecorder {

    private static final TypeReference<List<Candidate>> CANDIDATES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcLlmCallRecorder(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void record(LlmCallRecord c) {
        jdbc.sql("""
                INSERT INTO llm_call (tenant_id, execution_id, node_id, call_index, attempt, turn, seq, provider, model,
                    priority, candidates, reason, prompt_tokens, completion_tokens, cost_usd, latency_ms, outcome, error_code)
                VALUES (:tenantId, :executionId, :nodeId, :callIndex, :attempt, :turn, :seq, :provider, :model,
                    :priority, CAST(:candidates AS jsonb), :reason, :promptTokens, :completionTokens, :costUsd, :latencyMs,
                    :outcome, :errorCode)
                """)
                .param("tenantId", c.tenantId().value())
                .param("executionId", c.executionId().value())
                .param("nodeId", c.nodeId())
                .param("callIndex", c.callIndex())
                .param("attempt", c.attempt())
                .param("turn", c.turn())
                .param("seq", c.seq())
                .param("provider", c.provider())
                .param("model", c.model())
                .param("priority", c.priority().name())
                .param("candidates", write(c.candidates()))
                .param("reason", c.reason())
                .param("promptTokens", c.promptTokens())
                .param("completionTokens", c.completionTokens())
                .param("costUsd", c.costUsd())
                .param("latencyMs", c.latencyMs())
                .param("outcome", c.outcome())
                .param("errorCode", c.errorCode())
                .update();
    }

    @Override
    public List<LlmCallRecord> findByExecution(TenantId tenantId, ExecutionId executionId) {
        return jdbc.sql("""
                SELECT * FROM llm_call WHERE tenant_id = :tenantId AND execution_id = :executionId
                ORDER BY call_index, attempt, turn, seq, id
                """)
                .param("tenantId", tenantId.value())
                .param("executionId", executionId.value())
                .query(this::map)
                .list();
    }

    private LlmCallRecord map(ResultSet rs, int row) throws SQLException {
        return new LlmCallRecord(TenantId.of(rs.getString("tenant_id")),
                new ExecutionId(rs.getObject("execution_id", UUID.class)), rs.getString("node_id"),
                rs.getInt("call_index"), rs.getInt("attempt"), rs.getInt("turn"), rs.getInt("seq"),
                rs.getString("provider"), rs.getString("model"), Priority.valueOf(rs.getString("priority")),
                read(rs.getString("candidates")), rs.getString("reason"), rs.getInt("prompt_tokens"),
                rs.getInt("completion_tokens"), rs.getBigDecimal("cost_usd"), rs.getLong("latency_ms"),
                rs.getString("outcome"), rs.getString("error_code"));
    }

    private String write(List<Candidate> candidates) {
        try {
            return mapper.writeValueAsString(candidates);
        } catch (JsonProcessingException e) {
            throw new NonRetryableError(ErrorCodes.INTERNAL, "cannot serialise router candidates", e);
        }
    }

    private List<Candidate> read(String json) {
        try {
            return mapper.readValue(json, CANDIDATES);
        } catch (JsonProcessingException e) {
            throw new NonRetryableError(ErrorCodes.INTERNAL, "cannot read router candidates", e);
        }
    }
}
