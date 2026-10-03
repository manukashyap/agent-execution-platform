package com.conversive.aep.tenancy;

import com.conversive.aep.common.TenantId;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory per-tenant token buckets (06 §4.11; a shared Redis limiter is stretch). Per replica, so
 * the effective limit is {@code replicas × rate}.
 */
public class TenantRateLimiter {

    private final Map<TenantId, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoTicker;

    public TenantRateLimiter(LongSupplier nanoTicker) {
        this.nanoTicker = nanoTicker;
    }

    /** @return {@link Duration#ZERO} when admitted, otherwise the suggested {@code Retry-After} */
    public Duration tryAcquire(TenantLimits limits) {
        long now = nanoTicker.getAsLong();
        double rate = limits.ratePerSec().doubleValue();
        int burst = limits.burst();
        TokenBucket bucket = buckets.compute(limits.tenantId(), (id, current) -> {
            if (current == null) {
                return new TokenBucket(rate, burst, now);
            }
            return current.matches(rate, burst) ? current : current.reconfigured(rate, burst, now);
        });
        return bucket.tryAcquire(now);
    }
}
