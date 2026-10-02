package com.conversive.aep.engine.temporal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Workflow start behaviour ({@code aep.engine.launcher.*}).
 *
 * @param startAttempts  inline attempts before giving up with {@code START_FAILED}
 * @param startBackoff   pause between attempts
 * @param retryAfter     {@code Retry-After} suggested to the client on {@code START_FAILED}
 * @param runTimeoutSlack added to the execution deadline to form the Temporal run timeout (the
 *                       workflow enforces the deadline itself; this is only a backstop)
 */
@ConfigurationProperties("aep.engine.launcher")
public record LauncherProperties(int startAttempts, Duration startBackoff, Duration retryAfter,
                                 Duration runTimeoutSlack) {

    public LauncherProperties {
        startAttempts = startAttempts <= 0 ? 3 : startAttempts;
        startBackoff = startBackoff == null ? Duration.ofMillis(200) : startBackoff;
        retryAfter = retryAfter == null ? Duration.ofSeconds(5) : retryAfter;
        runTimeoutSlack = runTimeoutSlack == null ? Duration.ofHours(1) : runTimeoutSlack;
    }

    public static LauncherProperties defaults() {
        return new LauncherProperties(0, null, null, null);
    }
}
