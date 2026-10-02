package com.conversive.aep.tenancy.persistence;

import com.conversive.aep.common.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Derived concurrency (06 §4.5): no counter, no lock; served by {@code workflow_execution_live_idx}. */
@Repository
public class LiveExecutionCounter {

    private final JdbcClient jdbc;

    public LiveExecutionCounter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** QUEUED/RUNNING executions whose deadline has not passed (an expired deadline frees a leaked slot). */
    public long countLive(TenantId tenantId, Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM workflow_execution
                WHERE tenant_id = :tenantId AND status IN ('QUEUED', 'RUNNING') AND deadline_at > :now
                """)
                .param("tenantId", tenantId.value())
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
    }
}
