package com.conversive.aep.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The cap is soft (count, then insert; no lock, 06 §4.5): each admitter can have at most one admitted
 * insert in flight when the count crosses the cap, so the overshoot is at most admitters - 1.
 */
class SoftCapRaceIT extends PostgresIntegrationTest {

    private static final int CAP = 5;
    private static final int ADMITTERS = 16;

    @Autowired
    AdmissionControl admission;
    @Autowired
    JdbcClient jdbc;

    @Test
    void concurrentAdmittersOvershootTheCapByLessThanTheirNumber() throws Exception {
        TenantId tenant = new TestTenants(jdbc).create("t_race");
        jdbc.sql("UPDATE tenant_limits SET rate_per_sec = 100000, burst = 100000, max_concurrent = :cap "
                        + "WHERE tenant_id = :t")
                .param("cap", CAP).param("t", tenant.value()).update();
        jdbc.sql("INSERT INTO workflow_definition (tenant_id, workflow_id, def_version, spec, sha256) "
                        + "VALUES (:t, 'wf', 1, '{}'::jsonb, 'x')")
                .param("t", tenant.value()).update();

        int admitted = race(tenant);

        long live = liveRows(tenant);
        assertThat(live).isEqualTo(admitted);
        assertThat(live).isBetween((long) CAP, (long) CAP + ADMITTERS - 1);
        assertThatThrownBy(() -> admission.admit(tenant))
                .isInstanceOfSatisfying(RetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.CONCURRENCY_LIMIT));
    }

    private int race(TenantId tenant) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(ADMITTERS);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < ADMITTERS; i++) {
                results.add(pool.submit(() -> admitUntilRejected(tenant, start)));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> f : results) {
                total += f.get();
            }
            return total;
        } finally {
            pool.shutdownNow();
        }
    }

    private int admitUntilRejected(TenantId tenant, CountDownLatch start) throws InterruptedException {
        start.await();
        int admitted = 0;
        while (true) {
            try {
                admission.admit(tenant);
            } catch (RetryableError e) {
                assertThat(e.code()).isEqualTo(ErrorCodes.CONCURRENCY_LIMIT);
                return admitted;
            }
            jdbc.sql("INSERT INTO workflow_execution (id, tenant_id, workflow_id, def_version, status, deadline_at) "
                            + "VALUES (:id, :t, 'wf', 1, 'QUEUED', now() + interval '1 hour')")
                    .param("id", UUID.randomUUID()).param("t", tenant.value()).update();
            admitted++;
        }
    }

    private long liveRows(TenantId tenant) {
        return jdbc.sql("SELECT count(*) FROM workflow_execution WHERE tenant_id = :t AND status = 'QUEUED'")
                .param("t", tenant.value()).query(Long.class).single();
    }
}
