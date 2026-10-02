package com.conversive.aep.cost;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.cost.persistence.BudgetRepository;
import com.conversive.aep.cost.persistence.BudgetRepository.NewReservation;
import com.conversive.aep.cost.persistence.BudgetRepository.Transition;
import com.conversive.aep.observability.AepMetrics;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-call TCC (06 §4.12). {@code try} reserves on the execution's cap and on the tenant budget in one
 * transaction (lock order execution → tenant everywhere); {@code confirm} is accepted from RESERVED
 * <em>or</em> CANCELLED, so a call that completed after its reservation was cancelled still records
 * its spend; {@code cancel} releases. Confirm/cancel of an unknown or settled reservation is a no-op.
 */
public class JdbcBudgetService implements BudgetService {

    private static final Logger log = LoggerFactory.getLogger(JdbcBudgetService.class);
    private static final int SCALE = 6;
    private static final String CONFIRMED_FROM_RESERVED = "RESERVED";
    static final String REJECTED_EXECUTION_CAP = "execution_cap";
    static final String REJECTED_TENANT_BUDGET = "tenant_budget";

    private final BudgetRepository repository;
    private final TransactionTemplate tx;
    private final CostProperties props;
    private final AepMetrics metrics;

    public JdbcBudgetService(BudgetRepository repository, TransactionTemplate tx, CostProperties props,
                             AepMetrics metrics) {
        this.repository = repository;
        this.tx = tx;
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    public Reservation tryReserve(TenantId tenantId, ExecutionId executionId, BigDecimal estimateUsd, String ref) {
        Objects.requireNonNull(estimateUsd, "estimateUsd");
        if (estimateUsd.signum() < 0) {
            throw new IllegalArgumentException("estimate must not be negative");
        }
        BigDecimal amount = estimateUsd.setScale(SCALE, RoundingMode.UP);
        ReservationRef parsed = ReservationRef.parse(ref);
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            reserveOnExecution(tenantId, executionId, amount);
            reserveOnTenant(tenantId, amount);
            repository.insert(new NewReservation(id, tenantId, executionId, parsed.nodeId(), parsed.callIndex(),
                    ref, amount));
        });
        return new Reservation(id.toString(), tenantId, amount);
    }

    @Override
    public void confirm(Reservation reservation, BigDecimal actualUsd) {
        Optional<UUID> id = idOf(reservation);
        if (id.isEmpty()) {
            return;
        }
        BigDecimal actual = (actualUsd == null ? BigDecimal.ZERO : actualUsd.max(BigDecimal.ZERO))
                .setScale(SCALE, RoundingMode.HALF_UP);
        tx.executeWithoutResult(status -> repository.markConfirmed(reservation.tenantId(), id.get(), actual)
                .ifPresent(t -> repository.settle(reservation.tenantId(), t.executionId(), released(t), actual)));
    }

    @Override
    public void cancel(Reservation reservation) {
        Optional<UUID> id = idOf(reservation);
        if (id.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(status -> repository.markCancelled(reservation.tenantId(), id.get())
                .ifPresent(t -> repository.settle(reservation.tenantId(), t.executionId(), t.amountUsd(),
                        BigDecimal.ZERO)));
    }

    private void reserveOnExecution(TenantId tenantId, ExecutionId executionId, BigDecimal amount) {
        if (repository.reserveOnExecution(tenantId, executionId, amount)) {
            return;
        }
        if (!repository.executionBudgetExists(tenantId, executionId)) {
            repository.createExecutionBudget(tenantId, executionId, props.dryRunMaxCostUsd(),
                    props.fallbackExecutionMaxCostUsd());
            if (repository.reserveOnExecution(tenantId, executionId, amount)) {
                return;
            }
        }
        metrics.budgetRejected(tenantId, REJECTED_EXECUTION_CAP);
        throw new NonRetryableError(ErrorCodes.BUDGET_EXCEEDED,
                "execution " + executionId + " has reached its cost limit (maxCostUsd)");
    }

    private void reserveOnTenant(TenantId tenantId, BigDecimal amount) {
        if (repository.reserveOnTenant(tenantId, amount)) {
            return;
        }
        if (!repository.tenantBudgetExists(tenantId)) {
            repository.createTenantBudget(tenantId, props.defaultTenantBudgetUsd());
            if (repository.reserveOnTenant(tenantId, amount)) {
                return;
            }
        }
        metrics.budgetRejected(tenantId, REJECTED_TENANT_BUDGET);
        throw new NonRetryableError(ErrorCodes.BUDGET_EXCEEDED, "tenant budget is exhausted");
    }

    /** A late confirm (after cancel) has nothing reserved left to release. */
    private static BigDecimal released(Transition t) {
        return CONFIRMED_FROM_RESERVED.equals(t.previousStatus()) ? t.amountUsd() : BigDecimal.ZERO;
    }

    private static Optional<UUID> idOf(Reservation reservation) {
        try {
            return Optional.of(UUID.fromString(reservation.id()));
        } catch (IllegalArgumentException e) {
            log.warn("ignoring settlement of a reservation not issued by the budget service: {}", reservation.id());
            return Optional.empty();
        }
    }

    /** Best-effort node identity from refs shaped {@code kind:nodeId:callIndex:…} (e.g. the router's). */
    record ReservationRef(String nodeId, Integer callIndex) {

        static ReservationRef parse(String ref) {
            String[] parts = ref == null ? new String[0] : ref.split(":");
            if (parts.length < 3) {
                return new ReservationRef(null, null);
            }
            try {
                return new ReservationRef(parts[1], Integer.parseInt(parts[2]));
            } catch (NumberFormatException e) {
                return new ReservationRef(null, null);
            }
        }
    }
}
