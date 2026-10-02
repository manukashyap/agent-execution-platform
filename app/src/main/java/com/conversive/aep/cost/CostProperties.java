package com.conversive.aep.cost;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Budget settings ({@code aep.cost.*}).
 *
 * @param defaultTenantBudgetUsd    limit of a {@code tenant_budget} row created on a tenant's first reservation
 * @param dryRunMaxCostUsd          per-execution cap of a {@code DRY_RUN} whose definition sets no
 *                                  {@code limits.max_cost_usd} (still capped by the tenant ceiling)
 * @param fallbackExecutionMaxCostUsd per-execution cap when the tenant has no {@code tenant_limits} row
 */
@ConfigurationProperties("aep.cost")
public record CostProperties(BigDecimal defaultTenantBudgetUsd, BigDecimal dryRunMaxCostUsd,
                             BigDecimal fallbackExecutionMaxCostUsd) {

    public CostProperties {
        defaultTenantBudgetUsd = defaultTenantBudgetUsd == null ? new BigDecimal("100") : defaultTenantBudgetUsd;
        dryRunMaxCostUsd = dryRunMaxCostUsd == null ? new BigDecimal("0.50") : dryRunMaxCostUsd;
        fallbackExecutionMaxCostUsd = fallbackExecutionMaxCostUsd == null
                ? new BigDecimal("5") : fallbackExecutionMaxCostUsd;
    }
}
