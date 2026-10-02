package com.conversive.aep.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EffectKeyTest {

    private static final TenantId TENANT = TenantId.of("t_dev");
    private static final ExecutionId EXEC = new ExecutionId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void isStableAcrossCallsSoEveryAttemptSharesTheKey() {
        EffectKey first = EffectKey.of(TENANT, EXEC, "charge", Phase.FORWARD, 0);
        EffectKey retry = EffectKey.of(TENANT, EXEC, "charge", Phase.FORWARD, 0);

        assertThat(retry).isEqualTo(first);
    }

    @Test
    void isLowerHexSha256OfTheColonJoinedParts() {
        EffectKey key = EffectKey.of(TENANT, EXEC, "charge", Phase.FORWARD, 0);

        assertThat(key.value())
                .isEqualTo(Hashing.sha256Hex("t_dev:00000000-0000-0000-0000-000000000001:charge:FORWARD:0"))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void differsByPhase() {
        assertThat(EffectKey.of(TENANT, EXEC, "charge", Phase.FORWARD, 0))
                .isNotEqualTo(EffectKey.of(TENANT, EXEC, "charge", Phase.COMPENSATE, 0));
    }

    @Test
    void differsByCallIndex() {
        assertThat(EffectKey.of(TENANT, EXEC, "send", Phase.FORWARD, 0))
                .isNotEqualTo(EffectKey.of(TENANT, EXEC, "send", Phase.FORWARD, 1));
    }

    @Test
    void differsByTenantExecutionAndNode() {
        EffectKey base = EffectKey.of(TENANT, EXEC, "charge", Phase.FORWARD, 0);

        assertThat(EffectKey.of(TenantId.of("t_other"), EXEC, "charge", Phase.FORWARD, 0)).isNotEqualTo(base);
        assertThat(EffectKey.of(TENANT, ExecutionId.of("00000000-0000-0000-0000-000000000002"), "charge",
                Phase.FORWARD, 0)).isNotEqualTo(base);
        assertThat(EffectKey.of(TENANT, EXEC, "refund", Phase.FORWARD, 0)).isNotEqualTo(base);
    }
}
