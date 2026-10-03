package com.conversive.aep.tenancy.persistence;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.tenancy.TenantTier;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TenantTierRepository {

    private final JdbcClient jdbc;

    public TenantTierRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TenantTier> findTier(TenantId tenantId) {
        return jdbc.sql("SELECT tier FROM tenant WHERE id = :tenantId")
                .param("tenantId", tenantId.value())
                .query((rs, n) -> TenantTier.valueOf(rs.getString(1)))
                .optional();
    }
}
