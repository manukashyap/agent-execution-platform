package com.conversive.aep.router;

import java.time.Duration;

/**
 * Thresholds of the provider health state machine (06 §4.7).
 *
 * @param halfOpenMaxFailures HALF_OPEN → HEALTHY needs at most this many failed probes (and probe p95 ≤ {@code halfOpenMaxP95Ms})
 */
public record HealthPolicy(
        Duration window,
        int lookbackWindows,
        int minSamples,
        long degradeP95Ms,
        int degradeWindows,
        long recoverP95Ms,
        int recoverWindows,
        double openErrorRate,
        long openP95Ms,
        Duration openCooldown,
        int halfOpenProbes,
        int halfOpenMaxFailures,
        long halfOpenMaxP95Ms,
        int degradedProbeEvery) {

    public static HealthPolicy defaults() {
        return new HealthPolicy(Duration.ofSeconds(10), 6, 20, 2_000, 2, 1_500, 3, 0.5, 5_000,
                Duration.ofSeconds(30), 10, 2, 2_000, 20);
    }
}
