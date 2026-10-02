package com.conversive.aep.cost.persistence;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code tenant_budget}, {@code execution_budget} and {@code budget_reservation} (V5). Reserving is a
 * single conditional {@code UPDATE … RETURNING} per counter row: Postgres re-checks the predicate on the
 * latest row version after a concurrent writer commits, so no read-modify-write race exists.
 */
@Repository
public class BudgetRepository {

    /** The reservation's state before a confirm/cancel transition. */
    public record Transition(String previousStatus, BigDecimal amountUsd, ExecutionId executionId) {
    }

    public record NewReservation(UUID id, TenantId tenantId, ExecutionId executionId, String nodeId,
                                 Integer callIndex, String ref, BigDecimal amountUsd) {
    }

    private final JdbcClient jdbc;

    public BudgetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean reserveOnTenant(TenantId tenantId, BigDecimal amount) {
        return jdbc.sql("""
                UPDATE tenant_budget SET reserved_usd = reserved_usd + :amount, updated_at = now()
                WHERE tenant_id = :tenantId AND spent_usd + reserved_usd + :amount <= limit_usd
                RETURNING tenant_id
                """)
                .param("tenantId", tenantId.value())
                .param("amount", amount)
                .query(String.class).optional().isPresent();
    }

    public boolean tenantBudgetExists(TenantId tenantId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM tenant_budget WHERE tenant_id = :tenantId)")
                .param("tenantId", tenantId.value())
                .query(Boolean.class).single();
    }

    public void createTenantBudget(TenantId tenantId, BigDecimal limit) {
        jdbc.sql("INSERT INTO tenant_budget (tenant_id, limit_usd) VALUES (:tenantId, :limit) ON CONFLICT DO NOTHING")
                .param("tenantId", tenantId.value())
                .param("limit", limit)
                .update();
    }

    public boolean reserveOnExecution(TenantId tenantId, ExecutionId executionId, BigDecimal amount) {
        return jdbc.sql("""
                UPDATE execution_budget SET reserved_usd = reserved_usd + :amount
                WHERE tenant_id = :tenantId AND execution_id = :executionId
                  AND spent_usd + reserved_usd + :amount <= limit_usd
                RETURNING execution_id
                """)
                .param("tenantId", tenantId.value())
                .param("executionId", executionId.value())
                .param("amount", amount)
                .query(UUID.class).optional().isPresent();
    }

    public boolean executionBudgetExists(TenantId tenantId, ExecutionId executionId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM execution_budget WHERE tenant_id = :tenantId AND execution_id = :executionId)
                """)
                .param("tenantId", tenantId.value())
                .param("executionId", executionId.value())
                .query(Boolean.class).single();
    }

    /**
     * Creates the execution's cap: the definition's {@code limits.max_cost_usd} when set, otherwise the
     * tenant ceiling ({@code tenant_limits.max_cost_usd}), lowered to {@code dryRunCap} for a {@code DRY_RUN}.
     */
    public void createExecutionBudget(TenantId tenantId, ExecutionId executionId, BigDecimal dryRunCap,
                                      BigDecimal fallbackCap) {
        jdbc.sql("""
                INSERT INTO execution_budget (tenant_id, execution_id, limit_usd)
                SELECT :tenantId, :executionId, CASE
                         WHEN s.explicit IS NOT NULL THEN s.explicit
                         WHEN s.mode = 'DRY_RUN' THEN LEAST(s.ceiling, :dryRunCap)
                         ELSE s.ceiling END
                FROM (SELECT
                        (SELECT CAST(COALESCE(d.spec #>> '{limits,max_cost_usd}', d.spec #>> '{limits,maxCostUsd}')
                                     AS numeric)
                           FROM workflow_execution e
                           JOIN workflow_definition d ON d.tenant_id = e.tenant_id
                                AND d.workflow_id = e.workflow_id AND d.def_version = e.def_version
                          WHERE e.tenant_id = :tenantId AND e.id = :executionId) AS explicit,
                        (SELECT e.mode FROM workflow_execution e
                          WHERE e.tenant_id = :tenantId AND e.id = :executionId) AS mode,
                        COALESCE((SELECT l.max_cost_usd FROM tenant_limits l WHERE l.tenant_id = :tenantId),
                                 :fallbackCap) AS ceiling) s
                ON CONFLICT (tenant_id, execution_id) DO NOTHING
                """)
                .param("tenantId", tenantId.value())
                .param("executionId", executionId.value())
                .param("dryRunCap", dryRunCap)
                .param("fallbackCap", fallbackCap)
                .update();
    }

    public void insert(NewReservation r) {
        jdbc.sql("""
                INSERT INTO budget_reservation (id, tenant_id, execution_id, node_id, call_index, ref, amount_usd)
                VALUES (:id, :tenantId, :executionId, :nodeId, :callIndex, :ref, :amount)
                """)
                .param("id", r.id())
                .param("tenantId", r.tenantId().value())
                .param("executionId", r.executionId().value())
                .param("nodeId", r.nodeId())
                .param("callIndex", r.callIndex())
                .param("ref", r.ref())
                .param("amount", r.amountUsd())
                .update();
    }

    /** RESERVED or CANCELLED → CONFIRMED with the actual cost; empty when unknown or already confirmed. */
    public Optional<Transition> markConfirmed(TenantId tenantId, UUID id, BigDecimal actual) {
        return jdbc.sql("""
                WITH prev AS (
                    SELECT id, status, amount_usd, execution_id FROM budget_reservation
                    WHERE tenant_id = :tenantId AND id = :id AND status IN ('RESERVED', 'CANCELLED')
                    FOR UPDATE)
                UPDATE budget_reservation r SET status = 'CONFIRMED', actual_usd = :actual, updated_at = now()
                FROM prev WHERE r.tenant_id = :tenantId AND r.id = prev.id
                RETURNING prev.status, prev.amount_usd, prev.execution_id
                """)
                .param("tenantId", tenantId.value())
                .param("id", id)
                .param("actual", actual)
                .query((rs, n) -> new Transition(rs.getString(1), rs.getBigDecimal(2),
                        new ExecutionId(rs.getObject(3, UUID.class))))
                .optional();
    }

    /** RESERVED → CANCELLED; empty when unknown or no longer RESERVED. */
    public Optional<Transition> markCancelled(TenantId tenantId, UUID id) {
        return jdbc.sql("""
                UPDATE budget_reservation SET status = 'CANCELLED', updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id AND status = 'RESERVED'
                RETURNING amount_usd, execution_id
                """)
                .param("tenantId", tenantId.value())
                .param("id", id)
                .query((rs, n) -> new Transition("RESERVED", rs.getBigDecimal(1),
                        new ExecutionId(rs.getObject(2, UUID.class))))
                .optional();
    }

    /** Releases {@code release} from reserved and adds {@code spend} to spent on both counter rows. */
    public void settle(TenantId tenantId, ExecutionId executionId, BigDecimal release, BigDecimal spend) {
        jdbc.sql("""
                UPDATE execution_budget SET reserved_usd = GREATEST(reserved_usd - :release, 0),
                    spent_usd = spent_usd + :spend
                WHERE tenant_id = :tenantId AND execution_id = :executionId
                """)
                .param("tenantId", tenantId.value())
                .param("executionId", executionId.value())
                .param("release", release)
                .param("spend", spend)
                .update();
        jdbc.sql("""
                UPDATE tenant_budget SET reserved_usd = GREATEST(reserved_usd - :release, 0),
                    spent_usd = spent_usd + :spend, updated_at = now()
                WHERE tenant_id = :tenantId
                """)
                .param("tenantId", tenantId.value())
                .param("release", release)
                .param("spend", spend)
                .update();
    }
}
