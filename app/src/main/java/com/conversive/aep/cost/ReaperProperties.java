package com.conversive.aep.cost;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Budget reservation reaper settings ({@code aep.cost.reaper.*}).
 *
 * @param enabled   run the reaper on a schedule
 * @param interval  delay between runs
 * @param maxAge    a RESERVED row older than this belongs to a call that died before confirm/cancel
 * @param batchSize rows cancelled per run at most
 */
@ConfigurationProperties("aep.cost.reaper")
public record ReaperProperties(Boolean enabled, Duration interval, Duration maxAge, Integer batchSize) {

    public ReaperProperties {
        enabled = enabled == null || enabled;
        interval = interval == null ? Duration.ofSeconds(60) : interval;
        maxAge = maxAge == null ? Duration.ofMinutes(15) : maxAge;
        batchSize = batchSize == null || batchSize < 1 ? 100 : batchSize;
    }
}
