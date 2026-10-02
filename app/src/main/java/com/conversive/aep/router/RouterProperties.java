package com.conversive.aep.router;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Router configuration ({@code aep.router.*}).
 *
 * @param providers            provider name to settings
 * @param tenantAllowList      tenant id to the providers it may use; tenants not listed may use every
 *                             provider (write tenant keys as {@code "[t_dev]"} so the binder keeps the underscore)
 * @param spill                where traffic goes when the spill-from provider's bucket is empty (01 §9)
 * @param noProviderRetryDelay {@code nextRetryDelay} when every provider is rate-limited or OPEN
 */
@ConfigurationProperties("aep.router")
public record RouterProperties(
        Map<String, Provider> providers,
        Map<String, List<String>> tenantAllowList,
        Spill spill,
        Duration noProviderRetryDelay) {

    public RouterProperties {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        tenantAllowList = tenantAllowList == null ? Map.of() : Map.copyOf(tenantAllowList);
        spill = spill == null ? new Spill("vllm", "llm-a", "llm-b") : spill;
        noProviderRetryDelay = noProviderRetryDelay == null ? Duration.ofSeconds(1) : noProviderRetryDelay;
    }

    /**
     * @param nominalLatency           expected latency, used for scoring until the health tracker has observations
     * @param costPerThousandTokensUsd USD per 1,000 tokens (prompt + completion)
     * @param rps                      token-bucket capacity and refill rate per second
     */
    public record Provider(
            String baseUrl,
            String model,
            Duration nominalLatency,
            BigDecimal costPerThousandTokensUsd,
            int rps,
            Set<String> capabilities) {
    }

    public record Spill(String from, String highTo, String defaultTo) {
    }
}
