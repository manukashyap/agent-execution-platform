package com.conversive.aep.tenancy.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.tenancy.TenantLimits;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TenantLimitsRepositoryIT extends PostgresIntegrationTest {

    @Autowired
    TenantLimitsRepository repository;

    @Test
    void seededTenantHasTheV1Defaults() {
        TenantLimits limits = repository.find(new TenantId("t_dev")).orElseThrow();

        assertThat(limits.maxCostUsd()).isEqualByComparingTo("5");
        assertThat(limits.maxTokens()).isEqualTo(200_000);
        assertThat(limits.maxNodeExecutions()).isEqualTo(500);
        assertThat(limits.maxFanout()).isEqualTo(100);
        assertThat(limits.maxDurationS()).isEqualTo(3600);
        assertThat(limits.maxConcurrent()).isEqualTo(50);
    }

    @Test
    void unknownTenantIsEmpty() {
        assertThat(repository.find(new TenantId("t_nobody"))).isEmpty();
    }
}
