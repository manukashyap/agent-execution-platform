package com.conversive.aep.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.TenantId;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TenantRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final TenantRateLimiter limiter = new TenantRateLimiter(nanos::get);

    static TenantLimits limits(String tenant, String rate, int burst, int maxConcurrent) {
        TenantLimits d = TenantLimits.defaults(TenantId.of(tenant));
        return new TenantLimits(d.tenantId(), new BigDecimal(rate), burst, maxConcurrent, d.maxCostUsd(),
                d.maxTokens(), d.maxNodeExecutions(), d.maxFanout(), d.maxToolCalls(), d.maxDurationS());
    }

    @Test
    void oneTenantExhaustingItsBucketDoesNotTouchAnother() {
        TenantLimits a = limits("t_a", "1", 2, 50);
        TenantLimits b = limits("t_b", "1", 2, 50);

        assertThat(limiter.tryAcquire(a)).isZero();
        assertThat(limiter.tryAcquire(a)).isZero();
        assertThat(limiter.tryAcquire(a)).isEqualTo(Duration.ofSeconds(1));

        assertThat(limiter.tryAcquire(b)).isZero();
        assertThat(limiter.tryAcquire(b)).isZero();
    }

    @Test
    void changedLimitsTakeEffectWithoutLosingState() {
        assertThat(limiter.tryAcquire(limits("t_c", "1", 1, 50))).isZero();

        assertThat(limiter.tryAcquire(limits("t_c", "1", 5, 50))).isEqualTo(Duration.ofSeconds(1));
        nanos.addAndGet(3_000_000_000L);
        assertThat(limiter.tryAcquire(limits("t_c", "1", 5, 50))).isZero();
    }
}
