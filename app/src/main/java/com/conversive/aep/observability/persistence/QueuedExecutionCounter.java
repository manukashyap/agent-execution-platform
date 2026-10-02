package com.conversive.aep.observability.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Executions admitted but not yet started, platform-wide. Deliberately not tenant-scoped: it feeds the
 * {@code queue_depth} gauge, which must not carry a tenant label, and returns a count only (no tenant data).
 */
@Repository
public class QueuedExecutionCounter {

    private final JdbcClient jdbc;

    public QueuedExecutionCounter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long countQueued() {
        return jdbc.sql("SELECT count(*) FROM workflow_execution WHERE status = 'QUEUED'")
                .query(Long.class)
                .single();
    }
}
