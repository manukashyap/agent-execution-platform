package com.conversive.aep.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** T7.2 per-call TCC on tenant_budget plus the per-execution cap (06 §4.12). */
class JdbcBudgetServiceIT extends PostgresIntegrationTest {

    private static final BigDecimal ONE = BigDecimal.ONE;

    @Autowired
    BudgetService budget;
    @Autowired
    JdbcClient jdbc;

    private TenantId tenant;

    @BeforeEach
    void setUp() {
        tenant = new TestTenants(jdbc).create("t_budget");
    }

    @Test
    void theP7ImplementationIsTheActiveBudgetService() {
        assertThat(budget).isInstanceOf(JdbcBudgetService.class);
    }

    @Test
    void fiftyConcurrentReservationsAgainstABudgetForTenAdmitExactlyTen() throws Exception {
        tenantBudget("10");

        List<Boolean> outcomes = concurrently(50, i -> budget.tryReserve(tenant, ExecutionId.random(), ONE, "llm:n:" + i));

        assertThat(outcomes).filteredOn(ok -> ok).hasSize(10);
        assertThat(counters("tenant_budget")).containsEntry("reserved", new BigDecimal("10.000000"));
        assertThat(jdbc.sql("SELECT count(*) FROM budget_reservation WHERE tenant_id = :t")
                .param("t", tenant.value()).query(Long.class).single()).isEqualTo(10L);
    }

    @Test
    void confirmMovesTheReservationToSpentAtTheActualAmount() {
        tenantBudget("10");
        Reservation r = budget.tryReserve(tenant, ExecutionId.random(), ONE, "llm:summarize:2:1:0:0");

        budget.confirm(r, new BigDecimal("0.25"));
        budget.confirm(r, new BigDecimal("0.25"));

        assertThat(counters("tenant_budget"))
                .containsEntry("reserved", new BigDecimal("0.000000"))
                .containsEntry("spent", new BigDecimal("0.250000"));
        Map<String, Object> row = reservation(r);
        assertThat(row).containsEntry("status", "CONFIRMED").containsEntry("node_id", "summarize")
                .containsEntry("call_index", 2);
    }

    @Test
    void cancelReleasesTheReservationAndALateConfirmStillRecordsTheSpend() {
        tenantBudget("10");
        Reservation r = budget.tryReserve(tenant, ExecutionId.random(), ONE, "llm:n:0:1:0:0");

        budget.cancel(r);
        assertThat(counters("tenant_budget")).containsEntry("reserved", new BigDecimal("0.000000"));

        budget.confirm(r, new BigDecimal("0.8"));
        assertThat(counters("tenant_budget"))
                .containsEntry("reserved", new BigDecimal("0.000000"))
                .containsEntry("spent", new BigDecimal("0.800000"));
        assertThat(reservation(r)).containsEntry("status", "CONFIRMED")
                .containsEntry("actual_usd", new BigDecimal("0.800000"));
        budget.cancel(r);
        assertThat(reservation(r)).containsEntry("status", "CONFIRMED");
    }

    @Test
    void spentCountsAgainstTheBudget() {
        tenantBudget("2");
        budget.confirm(budget.tryReserve(tenant, ExecutionId.random(), ONE, "a"), new BigDecimal("1.5"));

        assertThatThrownBy(() -> budget.tryReserve(tenant, ExecutionId.random(), ONE, "b"))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.BUDGET_EXCEEDED));
        budget.tryReserve(tenant, ExecutionId.random(), new BigDecimal("0.5"), "c");
    }

    @Test
    void repeatedReservationsForOneExecutionStopAtItsCap() {
        tenantBudget("1000");
        ExecutionId execution = ExecutionId.random();

        int confirmed = 0;
        NonRetryableError stop = null;
        for (int i = 0; i < 20 && stop == null; i++) {
            try {
                budget.confirm(budget.tryReserve(tenant, execution, ONE, "llm:loop:" + i + ":1:0:0"), ONE);
                confirmed++;
            } catch (NonRetryableError e) {
                stop = e;
            }
        }

        // no workflow_execution row: the cap is the tenant ceiling tenant_limits.max_cost_usd (5)
        assertThat(confirmed).isEqualTo(5);
        assertThat(stop.code()).isEqualTo(ErrorCodes.BUDGET_EXCEEDED);
        assertThat(counters("tenant_budget")).containsEntry("spent", new BigDecimal("5.000000"));
        budget.tryReserve(tenant, ExecutionId.random(), ONE, "other-execution");
    }

    @Test
    void executionCapComesFromTheDefinitionLimitsAndDryRunsDefaultLower() {
        tenantBudget("1000");
        definition("capped", "{\"limits\":{\"max_cost_usd\":2}}");
        definition("plain", "{}");
        ExecutionId capped = execution("capped", "LIVE");
        ExecutionId dryRun = execution("plain", "DRY_RUN");
        ExecutionId live = execution("plain", "LIVE");

        assertThat(reservableDollars(capped)).isEqualTo(2);
        assertThat(reservableDollars(dryRun)).isZero();
        budget.tryReserve(tenant, dryRun, new BigDecimal("0.5"), "dry");
        assertThat(reservableDollars(live)).isEqualTo(5);
    }

    @Test
    void concurrentReservationsForOneExecutionNeverPassItsCap() throws Exception {
        tenantBudget("1000");
        ExecutionId execution = ExecutionId.random();

        List<Boolean> outcomes = concurrently(30, i -> budget.tryReserve(tenant, execution, ONE, "fe:" + i));

        assertThat(outcomes).filteredOn(ok -> ok).hasSize(5);
    }

    @Test
    void aTenantWithoutABudgetRowGetsTheConfiguredDefault() {
        budget.tryReserve(tenant, ExecutionId.random(), ONE, "first");

        assertThat(counters("tenant_budget")).containsEntry("limit", new BigDecimal("100.000000"));
    }

    private interface Call {
        void run(int i);
    }

    private List<Boolean> concurrently(int n, Call call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        call.run(index);
                        return true;
                    } catch (NonRetryableError e) {
                        assertThat(e.code()).isEqualTo(ErrorCodes.BUDGET_EXCEEDED);
                        return false;
                    }
                }));
            }
            start.countDown();
            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> f : futures) {
                outcomes.add(f.get());
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private int reservableDollars(ExecutionId execution) {
        int n = 0;
        try {
            while (n < 100) {
                budget.tryReserve(tenant, execution, ONE, "probe:" + n);
                n++;
            }
        } catch (NonRetryableError e) {
            assertThat(e.code()).isEqualTo(ErrorCodes.BUDGET_EXCEEDED);
        }
        return n;
    }

    private void tenantBudget(String limit) {
        jdbc.sql("INSERT INTO tenant_budget (tenant_id, limit_usd) VALUES (:t, CAST(:l AS numeric))")
                .param("t", tenant.value()).param("l", limit).update();
    }

    private void definition(String workflowId, String spec) {
        jdbc.sql("INSERT INTO workflow_definition (tenant_id, workflow_id, def_version, spec, sha256) "
                        + "VALUES (:t, :w, 1, CAST(:s AS jsonb), 'x')")
                .param("t", tenant.value()).param("w", workflowId).param("s", spec).update();
    }

    private ExecutionId execution(String workflowId, String mode) {
        ExecutionId id = ExecutionId.random();
        jdbc.sql("INSERT INTO workflow_execution (id, tenant_id, workflow_id, def_version, mode, status, deadline_at) "
                        + "VALUES (:id, :t, :w, 1, :m, 'RUNNING', now() + interval '1 hour')")
                .param("id", id.value()).param("t", tenant.value()).param("w", workflowId).param("m", mode).update();
        return id;
    }

    private Map<String, Object> counters(String table) {
        return jdbc.sql("SELECT limit_usd AS limit, reserved_usd AS reserved, spent_usd AS spent FROM " + table
                        + " WHERE tenant_id = :t")
                .param("t", tenant.value()).query().singleRow();
    }

    private Map<String, Object> reservation(Reservation r) {
        return jdbc.sql("SELECT status, node_id, call_index, actual_usd FROM budget_reservation "
                        + "WHERE tenant_id = :t AND id = CAST(:id AS uuid)")
                .param("t", tenant.value()).param("id", r.id()).query().singleRow();
    }
}
