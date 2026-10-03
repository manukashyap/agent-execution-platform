package com.conversive.aep.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemporalPriorityPolicyTest {

    private static final TenantId ENT = TenantId.of("t_ent");
    private static final TenantId STD = TenantId.of("t_std");
    private static final TenantId FREE = TenantId.of("t_free");

    private final TemporalPriorityPolicy policy = new TemporalPriorityPolicy(Map.of(
            ENT, TenantTier.ENTERPRISE, STD, TenantTier.STANDARD, FREE, TenantTier.FREE)::get);

    @Test
    void priorityKeyComesFromTheTierAndIsNudgedByTheExecutionPriority() {
        assertThat(policy.priorityFor(ENT, Priority.NORMAL).getPriorityKey()).isEqualTo(2);
        assertThat(policy.priorityFor(STD, Priority.NORMAL).getPriorityKey()).isEqualTo(3);
        assertThat(policy.priorityFor(FREE, Priority.NORMAL).getPriorityKey()).isEqualTo(4);

        assertThat(policy.priorityFor(ENT, Priority.HIGH).getPriorityKey()).isEqualTo(1);
        assertThat(policy.priorityFor(FREE, Priority.LOW).getPriorityKey()).isEqualTo(5);
        assertThat(policy.priorityFor(STD, Priority.LOW).getPriorityKey()).isEqualTo(4);
    }

    @Test
    void fairnessKeyIsTheTenantWeightedByTier() {
        io.temporal.common.Priority ent = policy.priorityFor(ENT, Priority.NORMAL);
        io.temporal.common.Priority free = policy.priorityFor(FREE, Priority.NORMAL);

        assertThat(ent.getFairnessKey()).isEqualTo("t_ent");
        assertThat(free.getFairnessKey()).isEqualTo("t_free");
        assertThat(ent.getFairnessWeight()).isGreaterThan(policy.priorityFor(STD, Priority.NORMAL).getFairnessWeight());
        assertThat(free.getFairnessWeight()).isPositive();
    }

    @Test
    void unknownTierAndNullPriorityFallBackToStandardNormal() {
        io.temporal.common.Priority p = policy.priorityFor(TenantId.of("t_unknown"), null);

        assertThat(p.getPriorityKey()).isEqualTo(3);
        assertThat(TemporalPriorityPolicy.uniform().priorityFor(ENT, Priority.NORMAL).getPriorityKey()).isEqualTo(3);
    }
}
