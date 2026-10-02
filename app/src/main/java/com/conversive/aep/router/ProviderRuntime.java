package com.conversive.aep.router;

import java.time.Clock;

/** A provider with its live rate limiter and health tracker. */
public record ProviderRuntime(ProviderConfig config, TokenBucket bucket, ProviderHealthTracker health) {

    public static ProviderRuntime create(ProviderConfig config, HealthPolicy policy, Clock clock) {
        return new ProviderRuntime(config, new TokenBucket(config.rps(), config.rps(), clock),
                new ProviderHealthTracker(policy, clock));
    }

    public String name() {
        return config.name();
    }

    public ProviderHealthView view() {
        return ProviderHealthView.of(name(), health.snapshot(), bucket.available());
    }
}
