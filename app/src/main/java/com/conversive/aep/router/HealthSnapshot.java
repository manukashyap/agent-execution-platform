package com.conversive.aep.router;

/**
 * Health of one provider at a point in time.
 *
 * @param p95Ms          p95 of the latest qualifying window in the lookback, or -1 when there is none
 * @param errorRate      error rate of that window, or -1
 * @param windowSamples  samples in the still-open window
 */
public record HealthSnapshot(HealthState state, long p95Ms, double errorRate, int windowSamples) {

    public boolean observed() {
        return p95Ms >= 0;
    }
}
