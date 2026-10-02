package com.conversive.aep.sideeffect;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.http.UpstreamClientError;
import com.conversive.aep.observability.AepMetrics;
import com.conversive.aep.sideeffect.persistence.LedgerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The 06 §4.9 protocol: insert-or-read the ledger row, then return the stored result, wait out a live lease, or
 * take ownership and reconcile by idempotency mode. The external call runs outside any DB transaction.
 */
@Primary
@Component
public class LedgerSideEffectGuard implements SideEffectGuard {

    /** Each lost race means another attempt changed the row; re-reading a few times is enough. */
    private static final int MAX_ROW_RACES = 3;
    private static final int HTTP_CONFLICT = 409;
    private static final String IN_PROGRESS = "in_progress";

    private final LedgerRepository ledger;
    private final Clock clock;
    private final AepMetrics metrics;

    public LedgerSideEffectGuard(LedgerRepository ledger, Clock clock, AepMetrics metrics) {
        this.ledger = ledger;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    public JsonNode run(EffectSpec spec, EffectCall call) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SideEffectGuard.run must not be called inside a DB transaction");
        }
        for (int race = 0; race < MAX_ROW_RACES; race++) {
            Instant now = clock.instant();
            Instant lease = spec.leaseUntil(now);
            if (ledger.insertPending(spec, lease, now)) {
                return invokeAndCommit(spec, call, lease, true);
            }
            Optional<JsonNode> result = ledger.find(spec.tenantId(), spec.key())
                    .flatMap(row -> resume(spec, call, row, now, lease));
            if (result.isPresent()) {
                return result.get();
            }
        }
        throw new NonRetryableError(ErrorCodes.CONFLICT, "ledger row for effect " + spec.key() + " kept changing");
    }

    /** An existing row; empty means a concurrent attempt changed it first and the caller should re-read. */
    private Optional<JsonNode> resume(EffectSpec spec, EffectCall call, LedgerEntry row, Instant now, Instant lease) {
        if (row.state() == LedgerState.COMMITTED) {
            return Optional.of(row.response());
        }
        if (row.state() == LedgerState.FAILED) {
            throw new NonRetryableError(ErrorCodes.UPSTREAM_CLIENT_ERROR,
                    "effect " + spec.key() + " failed definitively on an earlier attempt");
        }
        // A live lease means in progress whoever owns it: an attempt number cannot tell a re-entry from a duplicate.
        if (row.state() == LedgerState.PENDING && row.leaseLiveAt(now)) {
            throw inProgress(spec, row.leaseUntil(), now);
        }
        // PENDING with an expired lease, or UNKNOWN.
        if (row.mode() == IdempotencyMode.NONE) {
            return escalate(spec, row, now);
        }
        if (!ledger.takeOwnership(row, spec.attempt(), lease, now)) {
            return Optional.empty();
        }
        return Optional.of(reconcile(spec, call, row.mode(), lease));
    }

    /** NONE: the earlier attempt may or may not have acted and nothing can tell; never call again. */
    private Optional<JsonNode> escalate(EffectSpec spec, LedgerEntry row, Instant now) {
        if (row.state() == LedgerState.PENDING) {
            if (!ledger.markUnknown(row, now)) {
                return Optional.empty();
            }
            metrics.sideEffectUnknown();
        }
        throw new NonRetryableError(ErrorCodes.NEEDS_ATTENTION,
                "effect " + spec.key() + " has an unknown outcome and its tool has no idempotency support");
    }

    private JsonNode reconcile(EffectSpec spec, EffectCall call, IdempotencyMode mode, Instant lease) {
        if (mode == IdempotencyMode.LOOKUP) {
            Optional<JsonNode> found = lookup(spec, call);
            if (found.isPresent()) {
                return commit(spec, call, found.get());
            }
        }
        // NATIVE_KEY (or LOOKUP found nothing): re-call with the same key; the provider returns the original result.
        return invokeAndCommit(spec, call, lease, false);
    }

    private Optional<JsonNode> lookup(EffectSpec spec, EffectCall call) {
        try {
            return call.lookup();
        } catch (RuntimeException e) {
            ledger.expireLease(spec.tenantId(), spec.key(), spec.attempt(), clock.instant());
            throw e;
        }
    }

    /** {@code createdRow}: this attempt inserted the row, so no earlier call with this key can have landed. */
    private JsonNode invokeAndCommit(EffectSpec spec, EffectCall call, Instant lease, boolean createdRow) {
        JsonNode response;
        try {
            response = call.invoke(spec.key().value());
        } catch (RuntimeException e) {
            throw onCallFailure(spec, lease, createdRow, e);
        }
        return commit(spec, call, response);
    }

    private RuntimeException onCallFailure(EffectSpec spec, Instant lease, boolean createdRow, RuntimeException error) {
        Instant now = clock.instant();
        if (providerInFlight(error)) {
            return new RetryableError(ErrorCodes.EFFECT_IN_PROGRESS,
                    "provider is still processing effect " + spec.key(), remaining(lease, now), error);
        }
        if (error instanceof NonRetryableError nonRetryable && ErrorCodes.RESPONSE_TOO_LARGE.equals(nonRetryable.code())) {
            // The provider answered, so the effect may have landed, but its body is unreadable: FAILED would make
            // compensation skip it, so park the row as UNKNOWN where reconciliation or a human can resolve it.
            if (ledger.markUnknownByOwner(spec.tenantId(), spec.key(), spec.attempt(), now)) {
                metrics.sideEffectUnknown();
            }
        } else if (error instanceof NonRetryableError) {
            ledger.markFailed(spec.tenantId(), spec.key(), spec.attempt(), now);
        } else if (error instanceof RetryableError retryable) {
            boolean notSent = ErrorCodes.UPSTREAM_RATE_LIMITED.equals(retryable.code())
                    || ErrorCodes.UPSTREAM_NOT_SENT.equals(retryable.code());
            // A 429 or a never-sent request proves only that this call did nothing; after a takeover an earlier
            // attempt's call may have landed, so the row must survive for compensation and the lease is merely ended.
            if (notSent && createdRow) {
                ledger.release(spec.tenantId(), spec.key(), spec.attempt());
            } else if (notSent || ErrorCodes.UPSTREAM_UNAVAILABLE.equals(retryable.code())) {
                ledger.expireLease(spec.tenantId(), spec.key(), spec.attempt(), now);
            }
        }
        // Timeouts, I/O errors and anything unexpected: the call may still land, so the lease keeps protecting it.
        return error;
    }

    private JsonNode commit(EffectSpec spec, EffectCall call, JsonNode response) {
        String externalRef = call.externalRef(response).orElse(null);
        if (ledger.commit(spec.tenantId(), spec.key(), spec.attempt(), response, externalRef, clock.instant())) {
            return response;
        }
        LedgerEntry row = ledger.find(spec.tenantId(), spec.key())
                .orElseThrow(() -> new NonRetryableError(ErrorCodes.INTERNAL, "ledger row vanished: " + spec.key()));
        if (row.state() == LedgerState.COMMITTED) {
            return row.response();
        }
        throw new NonRetryableError(ErrorCodes.INTERNAL,
                "effect " + spec.key() + " succeeded but its ledger row is " + row.state());
    }

    private static boolean providerInFlight(RuntimeException error) {
        if (error instanceof ProviderInFlightException) {
            return true;
        }
        if (error instanceof UpstreamClientError upstream && upstream.status() == HTTP_CONFLICT) {
            JsonNode body = upstream.body();
            JsonNode code = body == null ? null : body.get("error");
            return code == null || IN_PROGRESS.equals(code.asText());
        }
        return false;
    }

    private static RetryableError inProgress(EffectSpec spec, Instant leaseUntil, Instant now) {
        return new RetryableError(ErrorCodes.EFFECT_IN_PROGRESS,
                "effect " + spec.key() + " is owned by another attempt until " + leaseUntil, remaining(leaseUntil, now));
    }

    private static Duration remaining(Instant leaseUntil, Instant now) {
        Duration left = Duration.between(now, leaseUntil);
        return left.isNegative() ? Duration.ZERO : left;
    }
}
