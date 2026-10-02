package com.conversive.aep.engine.persistence;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code node_output}: the latest output per (node, call index). Every statement filters on tenant. */
@Repository
public class NodeOutputRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public NodeOutputRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record StoredOutput(String nodeId, int callIndex, JsonNode payload) {
    }

    /** Upsert: a re-run attempt replaces the output of an earlier attempt of the same call. */
    public void write(TenantId tenantId, ExecutionId executionId, String nodeId, int callIndex, int attempt,
                      String payloadJson, String sha256) {
        jdbc.sql("""
                INSERT INTO node_output (tenant_id, execution_id, node_id, call_index, attempt, payload, sha256)
                VALUES (:tenantId, :executionId, :nodeId, :callIndex, :attempt, CAST(:payload AS jsonb), :sha256)
                ON CONFLICT (execution_id, node_id, call_index) DO UPDATE
                   SET attempt = EXCLUDED.attempt, payload = EXCLUDED.payload, sha256 = EXCLUDED.sha256,
                       created_at = now()
                 WHERE node_output.tenant_id = EXCLUDED.tenant_id
                """)
                .param("tenantId", tenantId.value())
                .param("executionId", executionId.value())
                .param("nodeId", nodeId)
                .param("callIndex", callIndex)
                .param("attempt", attempt)
                .param("payload", payloadJson)
                .param("sha256", sha256)
                .update();
    }

    /** Outputs of the given nodes, ordered by node id then call index. */
    public List<StoredOutput> find(TenantId tenantId, ExecutionId executionId, Collection<String> nodeIds) {
        if (nodeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT node_id, call_index, payload::text AS payload
                  FROM node_output
                 WHERE tenant_id = :tenantId AND execution_id = :executionId AND node_id IN (:nodeIds)
                 ORDER BY node_id, call_index
                """)
                .param("tenantId", tenantId.value())
                .param("executionId", executionId.value())
                .param("nodeIds", List.copyOf(nodeIds))
                .query((rs, n) -> new StoredOutput(rs.getString("node_id"), rs.getInt("call_index"),
                        tree(rs.getString("payload"))))
                .list();
    }

    public int count(TenantId tenantId, ExecutionId executionId, String nodeId) {
        return jdbc.sql("""
                SELECT count(*) FROM node_output
                 WHERE tenant_id = :tenantId AND execution_id = :executionId AND node_id = :nodeId
                """)
                .param("tenantId", tenantId.value())
                .param("executionId", executionId.value())
                .param("nodeId", nodeId)
                .query(Integer.class)
                .single();
    }

    private JsonNode tree(String text) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored node output is not valid JSON", e);
        }
    }
}
