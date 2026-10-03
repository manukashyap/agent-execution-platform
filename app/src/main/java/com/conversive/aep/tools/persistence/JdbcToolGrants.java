package com.conversive.aep.tools.persistence;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.tools.ToolGrant;
import com.conversive.aep.tools.ToolGrants;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcToolGrants implements ToolGrants {

    private static final String SELECT = "SELECT tenant_id, tool_name, scopes FROM tenant_tool_grant WHERE tenant_id = :tenantId";

    private final JdbcClient jdbc;

    public JdbcToolGrants(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ToolGrant> find(TenantId tenantId, String toolName) {
        return jdbc.sql(SELECT + " AND tool_name = :toolName")
                .param("tenantId", tenantId.value())
                .param("toolName", toolName)
                .query(JdbcToolGrants::map)
                .optional();
    }

    @Override
    public List<ToolGrant> findAll(TenantId tenantId) {
        return jdbc.sql(SELECT + " ORDER BY tool_name")
                .param("tenantId", tenantId.value())
                .query(JdbcToolGrants::map)
                .list();
    }

    private static ToolGrant map(ResultSet rs, int row) throws SQLException {
        Array scopes = rs.getArray("scopes");
        return new ToolGrant(TenantId.of(rs.getString("tenant_id")), rs.getString("tool_name"),
                scopes == null ? List.of() : Arrays.asList((String[]) scopes.getArray()));
    }
}
