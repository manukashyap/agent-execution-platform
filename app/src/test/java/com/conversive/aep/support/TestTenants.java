package com.conversive.aep.support;

import com.conversive.aep.common.Hashing;
import com.conversive.aep.common.TenantId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Creates an isolated tenant (with default limits) and API keys for it. Plaintext keys are random per
 * run and only their SHA-256 is stored, so no key exists in source.
 */
public final class TestTenants {

    private final JdbcClient jdbc;

    public TestTenants(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public TenantId create(String prefix) {
        TenantId tenantId = TenantId.of(prefix + "_" + UUID.randomUUID().toString().substring(0, 8));
        jdbc.sql("INSERT INTO tenant (id, name) VALUES (:id, :id)").param("id", tenantId.value()).update();
        jdbc.sql("INSERT INTO tenant_limits (tenant_id) VALUES (:id)").param("id", tenantId.value()).update();
        return tenantId;
    }

    /** @return the plaintext key */
    public String apiKey(TenantId tenantId, String... scopes) {
        String plaintext = "test-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO api_key (key_hash, tenant_id, scopes) VALUES (:hash, :tenantId, :scopes)")
                .param("hash", Hashing.sha256Hex(plaintext))
                .param("tenantId", tenantId.value())
                .param("scopes", scopes)
                .update();
        return plaintext;
    }

    public void revoke(String plaintext) {
        jdbc.sql("UPDATE api_key SET revoked_at = now() WHERE key_hash = :hash")
                .param("hash", Hashing.sha256Hex(plaintext))
                .update();
    }
}
