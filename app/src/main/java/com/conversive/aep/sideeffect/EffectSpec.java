package com.conversive.aep.sideeffect;

import com.conversive.aep.common.EffectKey;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One guarded side effect. {@code key} must be {@link EffectKey#of} over the identity fields; use
 * {@link #of} to build it. {@code attempt} is the Temporal activity attempt (1-based).
 */
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

    public EffectSpec {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(startToClose, "startToClose");
        if (callIndex < 0 || attempt < 1) {
            throw new IllegalArgumentException("callIndex must be >= 0 and attempt >= 1");
        }
        if (!key.equals(EffectKey.of(tenantId, executionId, nodeId, phase, callIndex))) {
            throw new IllegalArgumentException("effect key does not match the effect identity");
        }
    }

    public static EffectSpec of(TenantId tenantId, ExecutionId executionId, String nodeId, Phase phase,
                                int callIndex, int attempt, IdempotencyMode mode, Duration startToClose) {
        EffectKey key = EffectKey.of(tenantId, executionId, nodeId, phase, callIndex);
        return new EffectSpec(key, tenantId, executionId, nodeId, phase, callIndex, attempt, mode, startToClose);
    }

    /** The HTTP timeout node executors must use for the guarded call ({@link TimingContract#httpTimeout}). */
    public Duration httpTimeout() {
        return TimingContract.httpTimeout(startToClose);
    }

    public Instant leaseUntil(Instant now) {
        return TimingContract.leaseUntil(now, startToClose);
    }
}
