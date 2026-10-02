package com.conversive.aep.tools.persistence;

import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.Reversibility;
import com.conversive.aep.tools.ToolDefinition;
import com.conversive.aep.tools.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The catalog is global (not tenant-scoped); per-tenant access is {@code tenant_tool_grant} (P4). */
@Repository
public class JdbcToolRegistry implements ToolRegistry {

    private static final String SELECT = """
            SELECT tool_name, version, description, input_schema::text, output_schema::text, scopes, timeout_ms,
                   retry::text, reversibility, idempotency, compensation_tool, lookup::text, dry_run_example::text
            FROM tool_registry
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcToolRegistry(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<ToolDefinition> find(String toolName) {
        return jdbc.sql(SELECT + " WHERE tool_name = :name")
                .param("name", toolName)
                .query(this::map)
                .optional();
    }

    @Override
    public List<ToolDefinition> findAll() {
        return jdbc.sql(SELECT + " ORDER BY tool_name").query(this::map).list();
    }

    private ToolDefinition map(ResultSet rs, int row) throws SQLException {
        return new ToolDefinition(
                rs.getString("tool_name"),
                rs.getInt("version"),
                rs.getString("description"),
                json(rs.getString("input_schema")),
                json(rs.getString("output_schema")),
                scopes(rs.getArray("scopes")),
                Duration.ofMillis(rs.getInt("timeout_ms")),
                json(rs.getString("retry")),
                Reversibility.valueOf(rs.getString("reversibility")),
                IdempotencyMode.valueOf(rs.getString("idempotency")),
                rs.getString("compensation_tool"),
                json(rs.getString("lookup")),
                json(rs.getString("dry_run_example")));
    }

    private static List<String> scopes(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private JsonNode json(String text) {
        if (text == null) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tool_registry holds invalid JSON", e);
        }
    }
}
