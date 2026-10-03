package com.conversive.aep.sideeffect;

import com.conversive.aep.common.EffectKey;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.TenantId;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;

/** One {@code side_effect_ledger} row (06 §4.9); {@code dbNow} is the database clock when it was read. */
public record LedgerEntry(
        EffectKey key,
        TenantId tenantId,
        ExecutionId executionId,
        String nodeId,
        Phase phase,
        int callIndex,
        LedgerState state,
        IdempotencyMode mode,
        int ownerAttempt,
        Instant leaseUntil,
        Instant dbNow,
        String externalRef,
        JsonNode response) {

    /**
     * Whether the lease was live when this row was read, judged on the database clock ({@code dbNow}) so that
     * nodes with skewed clocks agree. The argument is ignored and kept only for existing callers.
     */
    public boolean leaseLiveAt(Instant ignoredNodeClock) {
        return leaseLive();
    }

    public boolean leaseLive() {
        return leaseUntil.isAfter(dbNow);
    }

    /** Lease time left at read time on the database clock; never negative. */
    public Duration leaseRemaining() {
        Duration left = Duration.between(dbNow, leaseUntil);
        return left.isNegative() ? Duration.ZERO : left;
    }
}
