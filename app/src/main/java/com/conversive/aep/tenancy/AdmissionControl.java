package com.conversive.aep.tenancy;

import com.conversive.aep.common.TenantId;

/**
 * Gate in front of execution start (rate limit and concurrency cap, 06 §4.5). P1 ships
 * {@link AllowAllAdmissionControl}; the real limiter replaces that bean.
 */
public interface AdmissionControl {

    /**
     * @throws com.conversive.aep.common.RetryableError {@code RATE_LIMITED} or {@code CONCURRENCY_LIMIT},
     *         with {@code nextRetryDelay} as the suggested {@code Retry-After}
     */
    void admit(TenantId tenantId);
}
