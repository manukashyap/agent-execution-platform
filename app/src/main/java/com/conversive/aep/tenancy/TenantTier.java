package com.conversive.aep.tenancy;

/**
 * {@code tenant.tier}. Temporal priority keys run 1 (first) to 5; {@code fairnessWeight} is the tenant's
 * share of dispatch within one priority level when {@code matching.enableFairness} is on.
 */
public enum TenantTier {
    ENTERPRISE(2, 4f),
    STANDARD(3, 2f),
    FREE(4, 1f);

    private final int basePriorityKey;
    private final float fairnessWeight;

    TenantTier(int basePriorityKey, float fairnessWeight) {
        this.basePriorityKey = basePriorityKey;
        this.fairnessWeight = fairnessWeight;
    }

    public int basePriorityKey() {
        return basePriorityKey;
    }

    public float fairnessWeight() {
        return fairnessWeight;
    }
}
