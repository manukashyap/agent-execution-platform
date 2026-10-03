package com.conversive.aep.cost;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A reservation whose activity died before confirm/cancel is released by the reaper, both counters included. */
class BudgetReservationReaperIT extends PostgresIntegrationTest {

    @Autowired
    BudgetService budget;
    @Autowired
    BudgetReservationReaper reaper;
    @Autowired
    JdbcClient jdbc;

    @Test
    void anAbandonedReservationIsCancelledAndAFreshOneIsLeftAlone() {
        TenantId tenant = new TestTenants(jdbc).create("t_reaper");
        ExecutionId abandonedExecution = ExecutionId.random();
        Reservation abandoned = budget.tryReserve(tenant, abandonedExecution, new BigDecimal("2"), "llm:a:0");
        Reservation fresh = budget.tryReserve(tenant, ExecutionId.random(), BigDecimal.ONE, "llm:b:0");
        jdbc.sql("UPDATE budget_reservation SET created_at = now() - interval '1 hour' WHERE tenant_id = :t AND id = :id")
                .param("t", tenant.value()).param("id", UUID.fromString(abandoned.id())).update();

        int reaped = reaper.reapOnce();

        assertThat(reaped).isGreaterThanOrEqualTo(1);
        assertThat(status(tenant, abandoned)).isEqualTo("CANCELLED");
        assertThat(status(tenant, fresh)).isEqualTo("RESERVED");
        assertThat(jdbc.sql("SELECT reserved_usd FROM execution_budget WHERE tenant_id = :t AND execution_id = :e")
                .param("t", tenant.value()).param("e", abandonedExecution.value())
                .query(BigDecimal.class).single()).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT reserved_usd FROM tenant_budget WHERE tenant_id = :t")
                .param("t", tenant.value()).query(BigDecimal.class).single()).isEqualByComparingTo("1");
    }

    private String status(TenantId tenant, Reservation r) {
        return jdbc.sql("SELECT status FROM budget_reservation WHERE tenant_id = :t AND id = :id")
                .param("t", tenant.value()).param("id", UUID.fromString(r.id())).query(String.class).single();
    }
}
