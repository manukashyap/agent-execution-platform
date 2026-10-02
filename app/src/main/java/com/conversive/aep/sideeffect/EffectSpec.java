package com.conversive.aep.sideeffect;

import com.conversive.aep.common.EffectKey;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.TenantId;
import java.time.Duration;

public record EffectSpec(
        EffectKey key,
        TenantId tenantId,
        ExecutionId executionId,
        String nodeId,
        Phase phase,
        int callIndex,
        int attempt,
        IdempotencyMode mode,
        Duration startToClose) {
}
