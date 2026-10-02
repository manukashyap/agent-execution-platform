package com.conversive.aep.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.support.MutableClock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

    private final MutableClock clock = MutableClock.atWindowStart();

    @Test
    void startsFullAndRejectsWhenEmpty() {
        TokenBucket bucket = new TokenBucket(3, 3, clock);

        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
        assertThat(bucket.hasToken()).isFalse();
    }

    @Test
    void refillsContinuouslyUpToCapacity() {
        TokenBucket bucket = new TokenBucket(10, 10, clock);
        for (int i = 0; i < 10; i++) {
            bucket.tryAcquire();
        }

        clock.advance(Duration.ofMillis(250));
        assertThat(bucket.available()).isEqualTo(2.5);

        clock.advance(Duration.ofSeconds(5));
        assertThat(bucket.available()).isEqualTo(10.0);
    }

    @Test
    void rejectsNonPositiveSettings() {
        assertThatThrownBy(() -> new TokenBucket(0, 1, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(1, 0, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverHandsOutMoreTokensThanCapacityUnderConcurrency() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(100, 1, clock);
        AtomicInteger granted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 1_000; i++) {
            pool.submit(() -> {
                if (bucket.tryAcquire()) {
                    granted.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(granted.get()).isEqualTo(100);
    }
}
