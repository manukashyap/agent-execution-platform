package com.conversive.aep.tenancy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.tenancy.persistence.LiveExecutionCounter;
import java.time.Clock;
import java.time.Duration;

/**
 * T7.1 admission: per-tenant token bucket, then the soft concurrency cap. Runs after the idempotency
 * lookup, so a replayed POST consumes neither a token nor a slot. The rate check comes first because it
 * needs no DB read.
 */
public class TokenBucketAdmissionControl implements AdmissionControl {

    private final CachedTenantLimits limits;
    private final TenantRateLimiter rateLimiter;
    private final LiveExecutionCounter liveExecutions;
    private final TenancyProperties props;
    private final Clock clock;

    public TokenBucketAdmissionControl(CachedTenantLimits limits, TenantRateLimiter rateLimiter,
                                       LiveExecutionCounter liveExecutions, TenancyProperties props, Clock clock) {
        this.limits = limits;
        this.rateLimiter = rateLimiter;
        this.liveExecutions = liveExecutions;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void admit(TenantId tenantId) {
        TenantLimits tenant = limits.get(tenantId);
        Duration wait = rateLimiter.tryAcquire(tenant);
        if (!wait.isZero()) {
            throw new RetryableError(ErrorCodes.RATE_LIMITED,
                    "tenant rate limit of " + tenant.ratePerSec().toPlainString() + "/s exceeded", wait);
        }
        long live = liveExecutions.countLive(tenantId, clock.instant());
        if (live >= tenant.maxConcurrent()) {
            throw new RetryableError(ErrorCodes.CONCURRENCY_LIMIT,
                    "tenant has " + live + " running executions (limit " + tenant.maxConcurrent() + ")",
                    props.concurrencyRetryAfter());
        }
    }
}
