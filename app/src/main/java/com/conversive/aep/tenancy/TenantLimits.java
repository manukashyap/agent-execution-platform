package com.conversive.aep.tenancy;

import com.conversive.aep.common.TenantId;
import java.math.BigDecimal;
import java.util.Objects;

/** One {@code tenant_limits} row: the tenant's ceilings and admission parameters. */
public record TenantLimits(
        TenantId tenantId,
        BigDecimal ratePerSec,
        int burst,
        int maxConcurrent,
        BigDecimal maxCostUsd,
        long maxTokens,
        int maxNodeExecutions,
        int maxFanout,
        int maxToolCalls,
        int maxDurationS) {

    public TenantLimits {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(maxCostUsd, "maxCostUsd");
    }

    /** The V1 column defaults. */
    public static TenantLimits defaults(TenantId tenantId) {
        return new TenantLimits(tenantId, BigDecimal.TEN, 20, 50, new BigDecimal("5"), 200_000, 500, 100, 3, 3600);
    }
}
