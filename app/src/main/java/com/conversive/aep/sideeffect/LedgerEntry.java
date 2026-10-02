package com.conversive.aep.sideeffect;

import com.conversive.aep.common.EffectKey;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.TenantId;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** One {@code side_effect_ledger} row (06 §4.9). */
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
        String externalRef,
        JsonNode response) {

    public boolean leaseLiveAt(Instant now) {
        return leaseUntil.isAfter(now);
    }
}
