package com.conversive.aep.router;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** One LLM provider; {@code costPerThousandTokensUsd} is USD per 1,000 total (prompt + completion) tokens. */
public record ProviderConfig(
        String name,
        URI baseUrl,
        String model,
        Duration nominalLatency,
        BigDecimal costPerThousandTokensUsd,
        int rps,
        Set<String> capabilities) {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    public ProviderConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(nominalLatency, "nominalLatency");
        Objects.requireNonNull(costPerThousandTokensUsd, "costPerThousandTokensUsd");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        if (rps <= 0) {
            throw new IllegalArgumentException("rps must be positive for provider " + name);
        }
    }

    public static ProviderConfig from(String name, RouterProperties.Provider p) {
        return new ProviderConfig(name, URI.create(p.baseUrl()), p.model(), p.nominalLatency(),
                p.costPerThousandTokensUsd(), p.rps(), p.capabilities());
    }

    public BigDecimal costFor(long tokens) {
        return costPerThousandTokensUsd.multiply(BigDecimal.valueOf(tokens)).divide(THOUSAND, 6, RoundingMode.HALF_UP);
    }

    public URI completionsUri() {
        String base = baseUrl.toString();
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(trimmed + "/v1/chat/completions");
    }
}
