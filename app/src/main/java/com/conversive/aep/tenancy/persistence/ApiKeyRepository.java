package com.conversive.aep.tenancy.persistence;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.tenancy.ApiPrincipal;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * API key lookup by SHA-256 hash. This is the one query that is not filtered on tenant_id: it is how
 * the tenant is established in the first place.
 */
@Repository
public class ApiKeyRepository {

    private final JdbcClient jdbc;

    public ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ApiPrincipal> findActive(String keyHash) {
        return jdbc.sql("""
                        SELECT tenant_id, scopes FROM api_key
                        WHERE key_hash = :hash AND revoked_at IS NULL
                        """)
                .param("hash", keyHash)
                .query((rs, n) -> new ApiPrincipal(TenantId.of(rs.getString("tenant_id")),
                        scopes(rs.getArray("scopes"))))
                .optional();
    }

    private static Set<String> scopes(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        return new HashSet<>(Arrays.asList((String[]) array.getArray()));
    }
}
