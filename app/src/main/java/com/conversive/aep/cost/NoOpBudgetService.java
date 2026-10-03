package com.conversive.aep.cost;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/** Placeholder (every reservation succeeds); the P7 implementation supersedes it as {@code @Primary}. */
@Component
public class NoOpBudgetService implements BudgetService {

    @Override
    public Reservation tryReserve(TenantId tenantId, ExecutionId executionId, BigDecimal estimateUsd, String ref) {
        return new Reservation("noop:" + executionId + ":" + ref, tenantId, estimateUsd);
    }

    @Override
    public void confirm(Reservation reservation, BigDecimal actualUsd) {
        // nothing is tracked until P7
    }

    @Override
    public void cancel(Reservation reservation) {
        // nothing is tracked until P7
    }
}
