package com.conversive.aep.tenancy.persistence;

import com.conversive.aep.common.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Dev-only: stores the SHA-256 of the plaintext keys given via {@code AEP_DEV_API_KEY} /
 * {@code AEP_DEV_OTHER_API_KEY}, so no key (plain or hashed) is ever committed.
 */
@Component
@Profile("dev")
public class DevApiKeySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevApiKeySeeder.class);

    private final JdbcClient jdbc;
    private final DevKeys keys;

    public DevApiKeySeeder(JdbcClient jdbc, DevKeys keys) {
        this.jdbc = jdbc;
        this.keys = keys;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("t_dev", keys.devApiKey());
        seed("t_other", keys.otherApiKey());
    }

    private void seed(String tenantId, String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            log.warn("no dev API key configured for tenant {}; skipping", tenantId);
            return;
        }
        jdbc.sql("""
                INSERT INTO api_key (key_hash, tenant_id, scopes)
                VALUES (:hash, :tenantId, ARRAY['*'])
                ON CONFLICT (key_hash) DO NOTHING
                """)
                .param("hash", Hashing.sha256Hex(plaintext))
                .param("tenantId", tenantId)
                .update();
        log.info("dev API key ready for tenant {}", tenantId);
    }

    @ConfigurationProperties("aep.tenancy.dev")
    public record DevKeys(String devApiKey, String otherApiKey) {
    }
}
