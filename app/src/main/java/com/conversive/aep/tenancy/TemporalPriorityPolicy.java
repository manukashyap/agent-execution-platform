package com.conversive.aep.tenancy;

import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import java.util.function.Function;

/**
 * T7.3: the Temporal {@link io.temporal.common.Priority} of an execution's workflow (and so, by
 * inheritance, its activities). Priority key from the tenant tier, nudged one step by the execution's
 * own priority; fairness key = tenant id so one tenant's backlog cannot starve another at the same level
 * (needs {@code matching.enableFairness}, confirmed in P0).
 */
public class TemporalPriorityPolicy {

    private static final int HIGHEST_KEY = 1;
    private static final int LOWEST_KEY = 5;

    private final Function<TenantId, TenantTier> tiers;

    /** @param tiers tier lookup; {@code null} answers mean {@link TenantTier#STANDARD} */
    public TemporalPriorityPolicy(Function<TenantId, TenantTier> tiers) {
        this.tiers = tiers;
    }

    /** Every tenant treated as {@link TenantTier#STANDARD}. */
    public static TemporalPriorityPolicy uniform() {
        return new TemporalPriorityPolicy(tenant -> TenantTier.STANDARD);
    }

    public io.temporal.common.Priority priorityFor(TenantId tenantId, Priority executionPriority) {
        TenantTier tier = tiers.apply(tenantId);
        TenantTier effective = tier == null ? TenantTier.STANDARD : tier;
        int key = effective.basePriorityKey() + nudge(executionPriority);
        return io.temporal.common.Priority.newBuilder()
                .setPriorityKey(Math.clamp(key, HIGHEST_KEY, LOWEST_KEY))
                .setFairnessKey(tenantId.value())
                .setFairnessWeight(effective.fairnessWeight())
                .build();
    }

    private static int nudge(Priority priority) {
        if (priority == null) {
            return 0;
        }
        return switch (priority) {
            case HIGH -> -1;
            case NORMAL -> 0;
            case LOW -> 1;
        };
    }
}
