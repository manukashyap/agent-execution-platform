package com.conversive.aep.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void burstIsAvailableAtOnceThenTheNextTokenWaitsForTheRefill() {
        TokenBucket bucket = new TokenBucket(2.0, 3, 0);

        assertThat(bucket.tryAcquire(0)).isZero();
        assertThat(bucket.tryAcquire(0)).isZero();
        assertThat(bucket.tryAcquire(0)).isZero();

        assertThat(bucket.tryAcquire(0)).isEqualTo(Duration.ofMillis(500));
        assertThat(bucket.tryAcquire(SECOND / 4)).isEqualTo(Duration.ofMillis(250));
        assertThat(bucket.tryAcquire(SECOND / 2)).isZero();
    }

    @Test
    void refillNeverExceedsTheBurst() {
        TokenBucket bucket = new TokenBucket(10.0, 2, 0);

        long later = 60 * SECOND;
        assertThat(bucket.tryAcquire(later)).isZero();
        assertThat(bucket.tryAcquire(later)).isZero();
        assertThat(bucket.tryAcquire(later)).isPositive();
    }

    @Test
    void reconfiguringKeepsTokensButCapsThemAtTheNewBurst() {
        TokenBucket bucket = new TokenBucket(1.0, 10, 0);

        TokenBucket smaller = bucket.reconfigured(1.0, 1, 0);

        assertThat(smaller.tryAcquire(0)).isZero();
        assertThat(smaller.tryAcquire(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(smaller.matches(1.0, 1)).isTrue();
        assertThat(smaller.matches(2.0, 1)).isFalse();
    }
}
