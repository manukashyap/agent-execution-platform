package com.conversive.aep.sideeffect;

import com.conversive.aep.common.EffectKey;
import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.Reversibility;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.sideeffect.CompensationDecision.Kind;
import com.conversive.aep.sideeffect.persistence.LedgerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reconcile-then-compensate (06 §4.9 / T5.4): reads the FORWARD ledger row and decides whether the saga must
 * compensate it. Never calls the compensation itself; that goes through {@link SideEffectGuard} with
 * {@link Phase#COMPENSATE}, whose key differs from the forward key.
 */
@Component
public class CompensationReconciler {

    private static final int MAX_ROW_RACES = 3;

    private final LedgerRepository ledger;
    private final Clock clock;

    public CompensationReconciler(LedgerRepository ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    /** As {@link #decide(TenantId, ExecutionId, String, int, Reversibility, EffectCall)} without a lookup. */
    public CompensationDecision decide(TenantId tenantId, ExecutionId executionId, String nodeId, int callIndex,
                                       Reversibility forwardReversibility) {
        return decide(tenantId, executionId, nodeId, callIndex, forwardReversibility, key -> {
            throw new IllegalStateException("the reconciler never invokes the forward call");
        });
    }

    /**
     * @param forward only its {@link EffectCall#lookup()} and {@link EffectCall#externalRef} are used, for a
     *                LOOKUP-mode forward with an unresolved outcome; {@code invoke} is never called
     * @throws RetryableError {@code EFFECT_IN_PROGRESS} with the remaining lease while a forward attempt owns it
     */
    public CompensationDecision decide(TenantId tenantId, ExecutionId executionId, String nodeId, int callIndex,
                                       Reversibility forwardReversibility, EffectCall forward) {
        EffectKey key = EffectKey.of(tenantId, executionId, nodeId, Phase.FORWARD, callIndex);
        for (int race = 0; race < MAX_ROW_RACES; race++) {
            Optional<LedgerEntry> row = ledger.find(tenantId, key);
            if (row.isEmpty()) {
                return CompensationDecision.of(Kind.SKIP, "forward effect was never attempted");
            }
            Optional<CompensationDecision> decision = resolve(row.get(), forwardReversibility, forward);
            if (decision.isPresent()) {
                return decision.get();
            }
        }
        throw new NonRetryableError(ErrorCodes.CONFLICT, "forward ledger row " + key + " kept changing");
    }

    /** Empty means the row changed under a CAS and must be re-read. */
    private Optional<CompensationDecision> resolve(LedgerEntry row, Reversibility reversibility, EffectCall forward) {
        Instant now = clock.instant();
        return switch (row.state()) {
            case COMMITTED -> Optional.of(executed(reversibility, row.response()));
            case FAILED -> Optional.of(CompensationDecision.of(Kind.SKIP, "forward effect failed definitively"));
            case PENDING, UNKNOWN -> {
                if (row.state() == LedgerState.PENDING && row.leaseLiveAt(now)) {
                    throw inProgress(row, now);
                }
                yield unresolved(row, reversibility, forward, now);
            }
        };
    }

    /** PENDING with an expired lease, or UNKNOWN: the forward call may or may not have landed. */
    private Optional<CompensationDecision> unresolved(LedgerEntry row, Reversibility reversibility,
                                                      EffectCall forward, Instant now) {
        if (row.mode() == IdempotencyMode.NATIVE_KEY) {
            return Optional.of(CompensationDecision.of(Kind.RECONCILE_FORWARD,
                    "forward outcome unknown; re-run it with the same key to learn the result"));
        }
        if (row.mode() == IdempotencyMode.LOOKUP) {
            return lookup(row, reversibility, forward, now);
        }
        if (row.state() == LedgerState.PENDING && !ledger.markUnknown(row, now)) {
            return Optional.empty();
        }
        return Optional.of(CompensationDecision.of(Kind.NEEDS_ATTENTION,
                "forward outcome unknown and its tool has no idempotency support; not compensating blindly"));
    }

    private Optional<CompensationDecision> lookup(LedgerEntry row, Reversibility reversibility, EffectCall forward,
                                                  Instant now) {
        Optional<JsonNode> found = forward.lookup();
        if (found.isEmpty()) {
            return Optional.of(CompensationDecision.of(Kind.SKIP, "lookup found no forward effect"));
        }
        String externalRef = forward.externalRef(found.get()).orElse(null);
        if (!ledger.commit(row.tenantId(), row.key(), found.get(), externalRef, now)) {
            return Optional.empty();
        }
        return Optional.of(executed(reversibility, found.get()));
    }

    private static CompensationDecision executed(Reversibility reversibility, JsonNode response) {
        return switch (reversibility) {
            case COMPENSATABLE -> new CompensationDecision(Kind.COMPENSATE, response, "forward effect committed");
            case PIVOT -> new CompensationDecision(Kind.PIVOT_EXECUTED, response, "irreversible effect committed");
            case RETRIABLE, READ_ONLY -> CompensationDecision.of(Kind.SKIP, "tool has no compensation");
        };
    }

    private static RetryableError inProgress(LedgerEntry row, Instant now) {
        Duration left = Duration.between(now, row.leaseUntil());
        return new RetryableError(ErrorCodes.EFFECT_IN_PROGRESS,
                "forward effect " + row.key() + " is still owned until " + row.leaseUntil(), left);
    }
}
