package com.conversive.aep.definition.persistence;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.StoredDefinition;
import com.conversive.aep.definition.model.DefinitionCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code workflow_definition}: one immutable row per (tenant, workflow_id, version). */
@Repository
public class DefinitionRepository {

    public enum PublishOutcome { CREATED, ALREADY_PUBLISHED, VERSION_EXISTS }

    private static final String COLUMNS = "tenant_id, workflow_id, def_version, spec::text AS spec, sha256, created_at";

    private final JdbcClient jdbc;

    public DefinitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the version if it is new. An existing version with the same hash is an idempotent
     * re-publish; with a different hash it is a conflict (versions are immutable).
     */
    public PublishOutcome publish(StoredDefinition def) {
        int inserted = jdbc.sql("""
                INSERT INTO workflow_definition (tenant_id, workflow_id, def_version, spec, sha256)
                VALUES (:tenantId, :workflowId, :version, CAST(:spec AS jsonb), :sha256)
                ON CONFLICT (tenant_id, workflow_id, def_version) DO NOTHING
                """)
                .param("tenantId", def.tenantId().value())
                .param("workflowId", def.workflowId())
                .param("version", def.version())
                .param("spec", def.spec().toString())
                .param("sha256", def.sha256())
                .update();
        if (inserted == 1) {
            return PublishOutcome.CREATED;
        }
        String existing = find(def.tenantId(), def.workflowId(), def.version())
                .map(StoredDefinition::sha256)
                .orElseThrow(() -> new IllegalStateException("definition vanished after conflict"));
        return existing.equals(def.sha256()) ? PublishOutcome.ALREADY_PUBLISHED : PublishOutcome.VERSION_EXISTS;
    }

    public Optional<StoredDefinition> find(TenantId tenantId, String workflowId, int version) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM workflow_definition
                WHERE tenant_id = :tenantId AND workflow_id = :workflowId AND def_version = :version
                """)
                .param("tenantId", tenantId.value())
                .param("workflowId", workflowId)
                .param("version", version)
                .query(DefinitionRepository::map)
                .optional();
    }

    public Optional<StoredDefinition> findLatest(TenantId tenantId, String workflowId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM workflow_definition
                WHERE tenant_id = :tenantId AND workflow_id = :workflowId
                ORDER BY def_version DESC LIMIT 1
                """)
                .param("tenantId", tenantId.value())
                .param("workflowId", workflowId)
                .query(DefinitionRepository::map)
                .optional();
    }

    private static StoredDefinition map(ResultSet rs, int row) throws SQLException {
        return new StoredDefinition(
                TenantId.of(rs.getString("tenant_id")),
                rs.getString("workflow_id"),
                rs.getInt("def_version"),
                DefinitionCodec.readTree(rs.getString("spec")),
                rs.getString("sha256"),
                rs.getTimestamp("created_at").toInstant());
    }
}
