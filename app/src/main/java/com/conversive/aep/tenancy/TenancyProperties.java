package com.conversive.aep.tenancy;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Admission settings ({@code aep.tenancy.*}). The defaults apply to a tenant without a
 * {@code tenant_limits} row.
 *
 * @param limitsRefresh          how long a tenant's limits are cached before being re-read
 * @param concurrencyRetryAfter  {@code Retry-After} on {@code CONCURRENCY_LIMIT} (a slot frees when a run ends,
 *                               which the admitter cannot predict)
 */
@ConfigurationProperties("aep.tenancy")
public record TenancyProperties(BigDecimal defaultRatePerSec, int defaultBurst, int defaultMaxConcurrent,
                                Duration limitsRefresh, Duration concurrencyRetryAfter) {

    public TenancyProperties {
        defaultRatePerSec = defaultRatePerSec == null || defaultRatePerSec.signum() <= 0
                ? BigDecimal.TEN : defaultRatePerSec;
        defaultBurst = defaultBurst <= 0 ? 20 : defaultBurst;
        defaultMaxConcurrent = defaultMaxConcurrent <= 0 ? 50 : defaultMaxConcurrent;
        limitsRefresh = limitsRefresh == null ? Duration.ofSeconds(30) : limitsRefresh;
        concurrencyRetryAfter = concurrencyRetryAfter == null ? Duration.ofSeconds(5) : concurrencyRetryAfter;
    }
}
