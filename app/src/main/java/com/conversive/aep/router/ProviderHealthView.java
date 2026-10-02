package com.conversive.aep.router;

/**
 * Read-only router state per provider, for metrics (P8) and {@code /internal/router/state}.
 *
 * @param p95Ms     p95 of the latest qualifying window, null before the first one
 * @param errorRate error rate of that window, null before the first one
 */
public record ProviderHealthView(
        String provider, HealthState state, Long p95Ms, Double errorRate, int windowSamples, double bucketTokens) {

    static ProviderHealthView of(String provider, HealthSnapshot s, double bucketTokens) {
        return new ProviderHealthView(provider, s.state(), s.observed() ? s.p95Ms() : null,
                s.observed() ? s.errorRate() : null, s.windowSamples(), Math.floor(bucketTokens));
    }
}
