package com.conversive.aep.router;

import java.time.Clock;

/** Thread-safe token bucket: starts full, holds at most {@code capacity}, refills continuously at {@code perSecond}. */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerMilli;
    private final Clock clock;
    private double tokens;
    private long lastMillis;

    public TokenBucket(int capacity, int perSecond, Clock clock) {
        if (capacity <= 0 || perSecond <= 0) {
            throw new IllegalArgumentException("capacity and refill rate must be positive");
        }
        this.capacity = capacity;
        this.refillPerMilli = perSecond / 1000.0;
        this.clock = clock;
        this.tokens = capacity;
        this.lastMillis = clock.millis();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens < 1) {
            return false;
        }
        tokens -= 1;
        return true;
    }

    public synchronized boolean hasToken() {
        refill();
        return tokens >= 1;
    }

    public synchronized double available() {
        refill();
        return tokens;
    }

    private void refill() {
        long now = clock.millis();
        if (now > lastMillis) {
            tokens = Math.min(capacity, tokens + (now - lastMillis) * refillPerMilli);
            lastMillis = now;
        }
    }
}
