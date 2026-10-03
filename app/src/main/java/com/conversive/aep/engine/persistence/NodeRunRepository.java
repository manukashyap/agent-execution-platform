package com.conversive.aep.engine.persistence;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code node_run}: one row per (node, call index, phase, attempt). Every statement filters on tenant. A row
 * leaves RUNNING once: whichever of the attempt, its successor or the interpreter closes it first wins, so a
 * timed-out attempt's late write cannot overwrite the outcome the workflow already acted on.
 */
@Repository
public class NodeRunRepository {

    private static final int MAX_MESSAGE = 2000;

    private final JdbcClient jdbc;

    public NodeRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Identity of one {@code node_run} row. */
    public record RunKey(TenantId tenantId, ExecutionId executionId, String nodeId, int callIndex, Phase phase,
                         int attempt) {
    }

    /**
     * Inserts the attempt as RUNNING (a redelivered attempt restarts its row). Temporal starts attempt n only
     * after attempt n-1 failed or timed out, so an earlier row still RUNNING died without its catch block.
     */
    public void started(RunKey key, Instant at) {
        jdbc.sql("""
                UPDATE node_run SET status = 'FAILED', error_code = :code, error_message = :message, ended_at = :at
                 WHERE tenant_id = :tenantId AND execution_id = :executionId AND node_id = :nodeId
                   AND call_index = :callIndex AND phase = :phase AND attempt < :attempt AND status = 'RUNNING'
                """)
                .param("code", ErrorCodes.TIMEOUT)
                .param("message", "attempt ended without reporting; superseded by attempt " + key.attempt())
                .param("at", Timestamp.from(at))
                .param("tenantId", key.tenantId().value())
                .param("executionId", key.executionId().value())
                .param("nodeId", key.nodeId())
                .param("callIndex", key.callIndex())
                .param("phase", key.phase().name())
                .param("attempt", key.attempt())
                .update();
        jdbc.sql("""
                INSERT INTO node_run (tenant_id, execution_id, node_id, call_index, phase, attempt, status, started_at)
                VALUES (:tenantId, :executionId, :nodeId, :callIndex, :phase, :attempt, 'RUNNING', :at)
                ON CONFLICT (execution_id, node_id, call_index, phase, attempt) DO UPDATE
                   SET status = 'RUNNING', started_at = EXCLUDED.started_at, ended_at = NULL,
                       error_code = NULL, error_message = NULL
                 WHERE node_run.tenant_id = EXCLUDED.tenant_id
                """)
                .param("tenantId", key.tenantId().value())
                .param("executionId", key.executionId().value())
                .param("nodeId", key.nodeId())
                .param("callIndex", key.callIndex())
                .param("phase", key.phase().name())
                .param("attempt", key.attempt())
                .param("at", Timestamp.from(at))
                .update();
    }

    /** Closes a RUNNING attempt; a no-op when the interpreter or a later attempt already closed it. */
    public void finished(RunKey key, String status, String errorCode, String errorMessage, Instant at) {
        jdbc.sql("""
                UPDATE node_run SET status = :status, error_code = :errorCode, error_message = :errorMessage,
                       ended_at = :at
                 WHERE tenant_id = :tenantId AND execution_id = :executionId AND node_id = :nodeId
                   AND call_index = :callIndex AND phase = :phase AND attempt = :attempt AND status = 'RUNNING'
                """)
                .param("status", status)
                .param("errorCode", errorCode)
                .param("errorMessage", truncate(errorMessage))
                .param("at", Timestamp.from(at))
                .param("tenantId", key.tenantId().value())
                .param("executionId", key.executionId().value())
                .param("nodeId", key.nodeId())
                .param("callIndex", key.callIndex())
                .param("phase", key.phase().name())
                .param("attempt", key.attempt())
                .update();
    }

    /**
     * A node settled by the interpreter itself. Closes every FORWARD attempt of the node still RUNNING (its
     * activity died without recording, e.g. a StartToClose or heartbeat timeout); a node with no such row
     * (skipped, condition) gets one finished FORWARD row, attempt 1.
     */
    public void settled(TenantId tenantId, ExecutionId executionId, String nodeId, String status, String errorCode,
                        String errorMessage, Instant at) {
        jdbc.sql("""
                WITH closed AS (
                    UPDATE node_run SET status = :status, error_code = :errorCode, error_message = :errorMessage,
                           ended_at = :at
                     WHERE tenant_id = :tenantId AND execution_id = :executionId AND node_id = :nodeId
                       AND phase = 'FORWARD' AND status = 'RUNNING'
                    RETURNING 1)
                INSERT INTO node_run (tenant_id, execution_id, node_id, call_index, phase, attempt, status,
                    error_code, error_message, started_at, ended_at)
                SELECT :tenantId, :executionId, :nodeId, 0, 'FORWARD', 1, :status, :errorCode, :errorMessage, :at, :at
                 WHERE NOT EXISTS (SELECT 1 FROM closed)
                ON CONFLICT (execution_id, node_id, call_index, phase, attempt) DO NOTHING
                """)
                .param("tenantId", tenantId.value())
                .param("executionId", executionId.value())
                .param("nodeId", nodeId)
                .param("status", status)
                .param("errorCode", errorCode)
                .param("errorMessage", truncate(errorMessage))
                .param("at", Timestamp.from(at))
                .update();
    }

    private static String truncate(String message) {
        return message == null || message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE);
    }
}
