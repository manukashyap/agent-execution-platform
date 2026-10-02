package com.conversive.aep.engine.reconcile;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Execution reconciler settings ({@code aep.engine.reconciler.*}).
 *
 * @param enabled   run the reconciler on a schedule
 * @param interval  delay between runs
 * @param minAge    a RUNNING/COMPENSATING row untouched for less than this is never inspected (the workflow may
 *                  just be writing its terminal status)
 * @param batchSize rows inspected per run at most
 */
@ConfigurationProperties("aep.engine.reconciler")
public record ReconcilerProperties(Boolean enabled, Duration interval, Duration minAge, Integer batchSize) {

    public ReconcilerProperties {
        enabled = enabled == null || enabled;
        interval = interval == null ? Duration.ofSeconds(60) : interval;
        minAge = minAge == null || minAge.isNegative() ? Duration.ofMinutes(5) : minAge;
        batchSize = batchSize == null || batchSize < 1 ? 100 : batchSize;
    }
}
