package com.conversive.aep.common;

import java.util.Objects;

/**
 * Deterministic identity of one side effect. Attempt is deliberately excluded so every retry
 * of the same effect maps to the same key. This is the only place the key is computed.
 */
public record EffectKey(String value) {

    public EffectKey {
        Objects.requireNonNull(value, "value");
    }

    public static EffectKey of(TenantId tenantId, ExecutionId executionId, String nodeId, Phase phase, int callIndex) {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(phase, "phase");
        String raw = tenantId.value() + ":" + executionId.value() + ":" + nodeId + ":" + phase.name() + ":" + callIndex;
        return new EffectKey(Hashing.sha256Hex(raw));
    }

    @Override
    public String toString() {
        return value;
    }
}
