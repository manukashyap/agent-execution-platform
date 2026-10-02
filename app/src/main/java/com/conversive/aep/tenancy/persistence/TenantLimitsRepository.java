package com.conversive.aep.tenancy.persistence;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.tenancy.TenantLimits;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TenantLimitsRepository {

    private final JdbcClient jdbc;

    public TenantLimitsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TenantLimits> find(TenantId tenantId) {
        return jdbc.sql("""
                SELECT rate_per_sec, burst, max_concurrent, max_cost_usd, max_tokens, max_node_executions,
                       max_fanout, max_tool_calls, max_duration_s
                FROM tenant_limits WHERE tenant_id = :tenantId
                """)
                .param("tenantId", tenantId.value())
                .query((rs, n) -> new TenantLimits(tenantId,
                        rs.getBigDecimal("rate_per_sec"),
                        rs.getInt("burst"),
                        rs.getInt("max_concurrent"),
                        rs.getBigDecimal("max_cost_usd"),
                        rs.getLong("max_tokens"),
                        rs.getInt("max_node_executions"),
                        rs.getInt("max_fanout"),
                        rs.getInt("max_tool_calls"),
                        rs.getInt("max_duration_s")))
                .optional();
    }
}
