package com.conversive.aep.execution.persistence;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.execution.ExecutionStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code workflow_execution} (and read access to {@code node_run}). Every statement filters on
 * {@code tenant_id}; status changes go through {@link #cas} (optimistic, {@code row_version + 1}).
 */
@Repository
public class ExecutionRepository {

    private static final String COLUMNS = """
            id, tenant_id, workflow_id, def_version, mode, status, priority, row_version, idempotency_key,
            input::text AS input, options::text AS options, output::text AS output, error_code, error_message,
            started_at, deadline_at, ended_at, created_at, updated_at""";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ExecutionRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Inserts as QUEUED; false when the tenant already used this idempotency key (nothing written). */
    public boolean insert(NewExecution e) {
        return jdbc.sql("""
                INSERT INTO workflow_execution (id, tenant_id, workflow_id, def_version, mode, status, priority,
                    idempotency_key, input, options, deadline_at, created_at, updated_at)
                VALUES (:id, :tenantId, :workflowId, :defVersion, :mode, 'QUEUED', :priority, :idempotencyKey,
                    CAST(:input AS jsonb), CAST(:options AS jsonb), :deadlineAt, :createdAt, :createdAt)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """)
                .param("id", e.id().value())
                .param("tenantId", e.tenantId().value())
                .param("workflowId", e.workflowId())
                .param("defVersion", e.defVersion())
                .param("mode", e.mode().name())
                .param("priority", e.priority().name())
                .param("idempotencyKey", e.idempotencyKey())
                .param("input", json(e.input()))
                .param("options", json(e.options()))
                .param("deadlineAt", Timestamp.from(e.deadlineAt()))
                .param("createdAt", Timestamp.from(e.createdAt()))
                .update() == 1;
    }

    public Optional<ExecutionRecord> findById(TenantId tenantId, ExecutionId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM workflow_execution WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId.value())
                .param("id", id.value())
                .query(this::map)
                .optional();
    }

    public Optional<ExecutionRecord> findByIdempotencyKey(TenantId tenantId, String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM workflow_execution WHERE tenant_id = :tenantId AND idempotency_key = :key")
                .param("tenantId", tenantId.value())
                .param("key", idempotencyKey)
                .query(this::map)
                .optional();
    }

    /**
     * Moves the row to {@code to} iff its current status is in {@code allowedFrom}. Returns false (and
     * writes nothing) when the row is missing, belongs to another tenant, or is in another status.
     */
    public boolean cas(TenantId tenantId, ExecutionId id, Set<ExecutionStatus> allowedFrom, ExecutionStatus to,
                       StatusUpdate update) {
        if (allowedFrom.isEmpty()) {
            return false;
        }
        return jdbc.sql("""
                UPDATE workflow_execution
                   SET status = :to,
                       row_version = row_version + 1,
                       updated_at = :at,
                       started_at = CASE WHEN :to = 'RUNNING' THEN COALESCE(started_at, :at) ELSE started_at END,
                       ended_at = CASE WHEN :terminal THEN :at ELSE ended_at END,
                       error_code = COALESCE(:errorCode, error_code),
                       error_message = COALESCE(:errorMessage, error_message),
                       output = COALESCE(CAST(:output AS jsonb), output)
                 WHERE tenant_id = :tenantId AND id = :id AND status IN (:allowed)
                """)
                .param("to", to.name())
                .param("at", Timestamp.from(update.at()))
                .param("terminal", to.isTerminal())
                .param("errorCode", update.errorCode())
                .param("errorMessage", update.errorMessage())
                .param("output", json(update.output()))
                .param("tenantId", tenantId.value())
                .param("id", id.value())
                .param("allowed", allowedFrom.stream().map(Enum::name).toList())
                .update() == 1;
    }

    public List<NodeRunRecord> findNodeRuns(TenantId tenantId, ExecutionId id) {
        return jdbc.sql("""
                SELECT node_id, call_index, phase, attempt, status, error_code, error_message, started_at, ended_at
                  FROM node_run
                 WHERE tenant_id = :tenantId AND execution_id = :id
                 ORDER BY started_at NULLS LAST, node_id, call_index, phase, attempt
                """)
                .param("tenantId", tenantId.value())
                .param("id", id.value())
                .query((rs, n) -> new NodeRunRecord(rs.getString("node_id"), rs.getInt("call_index"),
                        rs.getString("phase"), rs.getInt("attempt"), rs.getString("status"),
                        rs.getString("error_code"), rs.getString("error_message"),
                        instant(rs, "started_at"), instant(rs, "ended_at")))
                .list();
    }

    private ExecutionRecord map(ResultSet rs, int row) throws SQLException {
        return new ExecutionRecord(
                new ExecutionId(rs.getObject("id", java.util.UUID.class)),
                TenantId.of(rs.getString("tenant_id")),
                rs.getString("workflow_id"),
                rs.getInt("def_version"),
                ExecutionMode.valueOf(rs.getString("mode")),
                ExecutionStatus.valueOf(rs.getString("status")),
                Priority.valueOf(rs.getString("priority")),
                rs.getLong("row_version"),
                rs.getString("idempotency_key"),
                tree(rs.getString("input")),
                tree(rs.getString("options")),
                tree(rs.getString("output")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                instant(rs, "started_at"),
                instant(rs, "deadline_at"),
                instant(rs, "ended_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private String json(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialise JSON column", e);
        }
    }

    private JsonNode tree(String text) {
        if (text == null) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON column is not valid JSON", e);
        }
    }
}
