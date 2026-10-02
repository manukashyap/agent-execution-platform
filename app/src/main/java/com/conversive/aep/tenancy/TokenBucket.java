package com.conversive.aep.tenancy;

import java.time.Duration;

/** Classic token bucket on a caller-supplied nano clock. Thread-safe; one per tenant. */
final class TokenBucket {

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private final double ratePerSec;
    private final int burst;
    private double tokens;
    private long lastNanos;

    TokenBucket(double ratePerSec, int burst, long nowNanos) {
        this(ratePerSec, burst, burst, nowNanos);
    }

    private TokenBucket(double ratePerSec, int burst, double tokens, long nowNanos) {
        if (ratePerSec <= 0 || burst <= 0) {
            throw new IllegalArgumentException("rate and burst must be positive");
        }
        this.ratePerSec = ratePerSec;
        this.burst = burst;
        this.tokens = Math.min(tokens, burst);
        this.lastNanos = nowNanos;
    }

    /** @return {@link Duration#ZERO} when a token was taken, otherwise how long until one is available */
    synchronized Duration tryAcquire(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1) {
            tokens -= 1;
            return Duration.ZERO;
        }
        long waitNanos = (long) Math.ceil((1 - tokens) / ratePerSec * NANOS_PER_SECOND);
        return Duration.ofNanos(Math.max(1, waitNanos));
    }

    boolean matches(double rate, int newBurst) {
        return ratePerSec == rate && burst == newBurst;
    }

    /** A bucket with new parameters that keeps the tokens accrued so far (capped at the new burst). */
    synchronized TokenBucket reconfigured(double rate, int newBurst, long nowNanos) {
        refill(nowNanos);
        return new TokenBucket(rate, newBurst, tokens, nowNanos);
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastNanos;
        if (elapsed > 0) {
            tokens = Math.min(burst, tokens + elapsed / NANOS_PER_SECOND * ratePerSec);
            lastNanos = nowNanos;
        }
    }
}
