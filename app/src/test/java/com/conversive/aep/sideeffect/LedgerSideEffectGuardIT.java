package com.conversive.aep.sideeffect;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.RetryableError;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Protocol branches of 06 §4.9 not covered by the PDF §9 scenarios. */
class LedgerSideEffectGuardIT extends LedgerTestSupport {

    private static final Duration START_TO_CLOSE = Duration.ofSeconds(10);

    @Autowired
    SideEffectGuard primaryGuard;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void ledgerGuardIsThePrimarySideEffectGuard() {
        assertThat(primaryGuard).isInstanceOf(LedgerSideEffectGuard.class);
    }

    @Test
    void firstRunCallsOnceSendsTheEffectKeyAndCommitsWithLeaseFromTheTimingContract() {
        stubCharge();
        EffectSpec spec = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);

        JsonNode result = guard.run(spec, charge(spec));

        assertThat(result.get("charge_id").asText()).isEqualTo("ch_1");
        assertThat(row(spec)).returns(LedgerState.COMMITTED, LedgerEntry::state)
                .returns("ch_1", LedgerEntry::externalRef)
                .returns(T0.plusSeconds(15), LedgerEntry::leaseUntil);
    }

    @Test
    void committedEffectReturnsTheStoredResponseWithoutCallingTheProvider() {
        stubCharge();
        EffectSpec spec = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        JsonNode first = guard.run(spec, charge(spec));
        AtomicInteger calls = new AtomicInteger();

        JsonNode again = guard.run(forward(2, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE), key -> {
            calls.incrementAndGet();
            return first;
        });

        assertThat(again).isEqualTo(first);
        assertThat(calls).hasValue(0);
    }

    @Test
    void definitiveClientErrorMarksTheRowFailedAndLaterAttemptsDoNotCallAgain() {
        wireMock.stubFor(post("/payments/charge").willReturn(aResponse().withStatus(402)));
        EffectSpec spec = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);

        assertThatThrownBy(() -> guard.run(spec, charge(spec))).isInstanceOf(NonRetryableError.class);
        assertThat(row(spec).state()).isEqualTo(LedgerState.FAILED);

        EffectSpec retry = forward(2, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        assertThatThrownBy(() -> guard.run(retry, charge(retry))).isInstanceOf(NonRetryableError.class);
        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo("/payments/charge")));
    }

    @Test
    void provider409InFlightBecomesEffectInProgressForTheRemainingLeaseAndLeavesTheRowUntouched() {
        wireMock.stubFor(post("/payments/charge").willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"in_progress\"}")));
        EffectSpec spec = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        EffectCall slowClock = key -> {
            clock.advance(Duration.ofSeconds(4));
            return charge(spec).invoke(key);
        };

        assertThatThrownBy(() -> guard.run(spec, slowClock))
                .isInstanceOfSatisfying(RetryableError.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCodes.EFFECT_IN_PROGRESS);
                    assertThat(e.nextRetryDelay()).isEqualTo(Duration.ofSeconds(11));
                });
        assertThat(row(spec)).returns(LedgerState.PENDING, LedgerEntry::state)
                .returns(1, LedgerEntry::ownerAttempt)
                .returns(T0.plusSeconds(15), LedgerEntry::leaseUntil);
    }

    @Test
    void providerInFlightExceptionFromANonHttpTransportIsAlsoEffectInProgress() {
        EffectSpec spec = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);

        assertThatThrownBy(() -> guard.run(spec, key -> {
            throw new ProviderInFlightException("busy");
        })).isInstanceOfSatisfying(RetryableError.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.EFFECT_IN_PROGRESS));
        assertThat(row(spec).state()).isEqualTo(LedgerState.PENDING);
    }

    @Test
    void serverErrorKeepsThePendingRowButEndsTheLeaseSoTheNextAttemptReconcilesAtOnce() {
        wireMock.stubFor(post("/payments/charge").willReturn(aResponse().withStatus(503)));
        EffectSpec spec = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);

        assertThatThrownBy(() -> guard.run(spec, charge(spec))).isInstanceOfSatisfying(RetryableError.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_UNAVAILABLE));
        assertThat(row(spec)).returns(LedgerState.PENDING, LedgerEntry::state).returns(T0, LedgerEntry::leaseUntil);

        stubCharge();
        EffectSpec retry = forward(2, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        assertThat(guard.run(retry, charge(retry)).get("charge_id").asText()).isEqualTo("ch_1");
    }

    @Test
    void rateLimitedCallReleasesTheRowBecauseTheProviderDidNothing() {
        wireMock.stubFor(post("/messages/send").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")));
        EffectSpec spec = forward(1, IdempotencyMode.NONE, START_TO_CLOSE);
        JsonNode body = mapper.createObjectNode().put("to", "+1555").put("body", "hi");

        assertThatThrownBy(() -> guard.run(spec, httpPost("/messages/send", spec, body)))
                .isInstanceOfSatisfying(RetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_RATE_LIMITED));

        assertThat(ledger.find(spec.tenantId(), spec.key())).isEmpty();
    }

    @Test
    void lookupModeCommitsAnEffectFoundByLookupWithoutCallingAgain() {
        EffectSpec attempt1 = spec("crm", Phase.FORWARD, 1, IdempotencyMode.LOOKUP,
                START_TO_CLOSE);
        assertThatThrownBy(() -> guard.run(attempt1, key -> {
            throw new RetryableError(ErrorCodes.UPSTREAM_TIMEOUT, "timeout");
        })).isInstanceOf(RetryableError.class);
        clock.advance(Duration.ofSeconds(15));
        JsonNode found = mapper.createObjectNode().put("external_ref", "lead_7");
        AtomicInteger calls = new AtomicInteger();

        JsonNode result = guard.run(attempt1Retry(), new EffectCall() {
            @Override
            public JsonNode invoke(String idempotencyKey) {
                calls.incrementAndGet();
                return found;
            }

            @Override
            public Optional<JsonNode> lookup() {
                return Optional.of(found);
            }
        });

        assertThat(result).isEqualTo(found);
        assertThat(calls).hasValue(0);
        assertThat(row(attempt1)).returns(LedgerState.COMMITTED, LedgerEntry::state)
                .returns("lead_7", LedgerEntry::externalRef);
    }

    @Test
    void lookupModeCallsWhenLookupFindsNothing() {
        EffectSpec attempt1 = spec("crm", Phase.FORWARD, 1, IdempotencyMode.LOOKUP,
                START_TO_CLOSE);
        assertThatThrownBy(() -> guard.run(attempt1, key -> {
            throw new RetryableError(ErrorCodes.UPSTREAM_TIMEOUT, "timeout");
        })).isInstanceOf(RetryableError.class);
        clock.advance(Duration.ofSeconds(15));
        JsonNode created = mapper.createObjectNode().put("external_ref", "lead_8");

        JsonNode result = guard.run(attempt1Retry(), key -> created);

        assertThat(result).isEqualTo(created);
        assertThat(row(attempt1).state()).isEqualTo(LedgerState.COMMITTED);
    }

    @Test
    void refusesToRunInsideADatabaseTransaction() {
        EffectSpec spec = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);

        assertThatThrownBy(() -> transactions.executeWithoutResult(s -> guard.run(spec, key -> null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ledger.find(spec.tenantId(), spec.key())).isEmpty();
    }

    private EffectSpec attempt1Retry() {
        return spec("crm", Phase.FORWARD, 2, IdempotencyMode.LOOKUP, START_TO_CLOSE);
    }

    private void stubCharge() {
        wireMock.stubFor(post("/payments/charge").willReturn(aResponse().withTransformers(IdempotentProviderStub.NAME)));
    }
}
