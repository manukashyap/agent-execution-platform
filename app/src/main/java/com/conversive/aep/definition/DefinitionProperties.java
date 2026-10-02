package com.conversive.aep.definition;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform caps and defaults for definitions ({@code aep.definition.*}). Zero/absent values fall
 * back to the documented defaults so a partial config block is safe.
 *
 * @param leaseGraceS minimum slack between StartToClose ({@code timeout_s}) and ScheduleToClose
 */
@ConfigurationProperties("aep.definition")
public record DefinitionProperties(
        int maxNodes,
        int maxWidth,
        int minTimeoutS,
        int maxTimeoutS,
        int maxRetryAttempts,
        int leaseGraceS,
        int defaultTimeoutS,
        int defaultRetryAttempts,
        long defaultInitialIntervalMs,
        int defaultMaxParallel,
        int defaultForEachConcurrency) {

    public DefinitionProperties {
        maxNodes = orDefault(maxNodes, 50);
        maxWidth = orDefault(maxWidth, 100);
        minTimeoutS = orDefault(minTimeoutS, 1);
        maxTimeoutS = orDefault(maxTimeoutS, 300);
        maxRetryAttempts = orDefault(maxRetryAttempts, 5);
        leaseGraceS = orDefault(leaseGraceS, 5);
        defaultTimeoutS = orDefault(defaultTimeoutS, 30);
        defaultRetryAttempts = orDefault(defaultRetryAttempts, 3);
        defaultInitialIntervalMs = defaultInitialIntervalMs <= 0 ? 1000 : defaultInitialIntervalMs;
        defaultMaxParallel = orDefault(defaultMaxParallel, 16);
        defaultForEachConcurrency = orDefault(defaultForEachConcurrency, 16);
    }

    public static DefinitionProperties defaults() {
        return new DefinitionProperties(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static int orDefault(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }
}
