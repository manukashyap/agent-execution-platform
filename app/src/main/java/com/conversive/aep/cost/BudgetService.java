package com.conversive.aep.cost;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import java.math.BigDecimal;

public interface BudgetService {

    /** Reserves an estimate; throws {@code NonRetryableError(BUDGET_EXCEEDED)} when the budget cannot cover it. */
    Reservation tryReserve(TenantId tenantId, ExecutionId executionId, BigDecimal estimateUsd, String ref);

    void confirm(Reservation reservation, BigDecimal actualUsd);

    void cancel(Reservation reservation);
}
