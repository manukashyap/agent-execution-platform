package com.conversive.aep.sideeffect;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
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
import com.conversive.aep.common.Reversibility;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.sideeffect.CompensationDecision.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T5.4 reconcile-then-compensate at guard level, including 06 §4.9 tests 4 and 5. */
class CompensationReconcilerIT extends LedgerTestSupport {

    private static final Duration START_TO_CLOSE = Duration.ofSeconds(10);
    private static final String NODE = "charge";

    private CompensationReconciler reconciler;

    @BeforeEach
    void reconciler() {
        reconciler = new CompensationReconciler(ledger, clock);
        wireMock.stubFor(post("/payments/charge").willReturn(aResponse().withTransformers(IdempotentProviderStub.NAME)));
    }

    @Test
    void refundAlwaysFailingWith500IsRetryableEveryTimeAndTheCompensateRowStaysPending() {
        EffectSpec forward = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        guard.run(forward, charge(forward));
        CompensationDecision decision = decide(Reversibility.COMPENSATABLE);
        assertThat(decision.kind()).isEqualTo(Kind.COMPENSATE);
        wireMock.stubFor(post("/payments/refund").willReturn(aResponse().withStatus(500)));
        JsonNode refundBody = mapper.createObjectNode()
                .put("charge_id", decision.forwardResponse().get("charge_id").asText());

        for (int attempt = 1; attempt <= 3; attempt++) {
            EffectSpec refund = spec(NODE, Phase.COMPENSATE, attempt, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
            assertThatThrownBy(() -> guard.run(refund, httpPost("/payments/refund", refund, refundBody)))
                    .isInstanceOfSatisfying(RetryableError.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_UNAVAILABLE));
            assertThat(row(refund).state()).isEqualTo(LedgerState.PENDING);
            advanceLeases(Duration.ofSeconds(1));
        }

        EffectSpec refund = spec(NODE, Phase.COMPENSATE, 1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        assertThat(refund.key()).isNotEqualTo(forward.key());
        assertThat(row(forward).state()).isEqualTo(LedgerState.COMMITTED);
        wireMock.verify(exactly(3), postRequestedFor(urlEqualTo("/payments/refund")));
    }

    @Test
    void unknownForwardWithModeNoneReturnsNeedsAttentionAndMakesNoCompensationCall() {
        EffectSpec forward = forward(1, IdempotencyMode.NONE, START_TO_CLOSE);
        leavePending(forward);
        advanceLeases(TimingContract.lease(START_TO_CLOSE));
        wireMock.resetRequests();

        for (int i = 0; i < 2; i++) {
            assertThat(decide(Reversibility.COMPENSATABLE).kind()).isEqualTo(Kind.NEEDS_ATTENTION);
        }

        assertThat(row(forward).state()).isEqualTo(LedgerState.UNKNOWN);
        wireMock.verify(exactly(0), anyRequestedFor(anyUrl()));
    }

    @Test
    void committedCompensatableForwardIsCompensatedWithItsStoredResponse() {
        EffectSpec forward = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        JsonNode charged = guard.run(forward, charge(forward));

        CompensationDecision decision = decide(Reversibility.COMPENSATABLE);

        assertThat(decision.kind()).isEqualTo(Kind.COMPENSATE);
        assertThat(decision.forwardResponse()).isEqualTo(charged);
    }

    @Test
    void committedPivotIsReportedAsPivotExecuted() {
        EffectSpec forward = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        guard.run(forward, charge(forward));

        assertThat(decide(Reversibility.PIVOT).kind()).isEqualTo(Kind.PIVOT_EXECUTED);
    }

    @Test
    void forwardNeverAttemptedIsSkipped() {
        assertThat(decide(Reversibility.COMPENSATABLE).kind()).isEqualTo(Kind.SKIP);
    }

    @Test
    void forwardFailedDefinitivelyIsSkipped() {
        wireMock.stubFor(post("/payments/charge").willReturn(aResponse().withStatus(402)));
        EffectSpec forward = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        assertThatThrownBy(() -> guard.run(forward, charge(forward))).isInstanceOf(NonRetryableError.class);

        assertThat(decide(Reversibility.COMPENSATABLE).kind()).isEqualTo(Kind.SKIP);
    }

    @Test
    void forwardStillLeasedIsInProgressUntilTheLeaseEnds() {
        EffectSpec forward = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        leavePending(forward);
        advanceLeases(Duration.ofSeconds(5));

        assertThatThrownBy(() -> decide(Reversibility.COMPENSATABLE))
                .isInstanceOfSatisfying(RetryableError.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCodes.EFFECT_IN_PROGRESS);
                    // the delay is the lease time left on the database clock, so node clock skew cannot shorten it
                    Duration left = TimingContract.lease(START_TO_CLOSE).minusSeconds(5);
                    assertThat(e.nextRetryDelay()).isBetween(left.minusSeconds(2), left);
                });
    }

    @Test
    void nativeKeyForwardWithExpiredLeaseIsReconciledByReRunningItThenCompensatedWithoutASecondCharge() {
        EffectSpec attempt1 = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        assertThatThrownBy(() -> guard.run(attempt1, key -> {
            charge(attempt1).invoke(key);
            throw new RetryableError(ErrorCodes.UPSTREAM_TIMEOUT, "response lost");
        })).isInstanceOf(RetryableError.class);
        advanceLeases(TimingContract.lease(START_TO_CLOSE));

        assertThat(decide(Reversibility.COMPENSATABLE).kind()).isEqualTo(Kind.RECONCILE_FORWARD);
        EffectSpec attempt2 = forward(2, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        guard.run(attempt2, charge(attempt2));

        CompensationDecision decision = decide(Reversibility.COMPENSATABLE);
        assertThat(decision.kind()).isEqualTo(Kind.COMPENSATE);
        assertThat(decision.forwardResponse().get("charge_id").asText()).isEqualTo("ch_1");
        assertThat(PROVIDER.performedEffects()).isEqualTo(1);
    }

    @Test
    void lookupForwardFoundByLookupIsCommittedAndCompensated() {
        EffectSpec forward = forward(1, IdempotencyMode.LOOKUP, START_TO_CLOSE);
        leavePending(forward);
        advanceLeases(TimingContract.lease(START_TO_CLOSE));
        JsonNode found = mapper.createObjectNode().put("external_ref", "crm_9");

        CompensationDecision decision = reconciler.decide(TENANT, execution, NODE, 0,
                Reversibility.COMPENSATABLE, lookupReturning(Optional.of(found)));

        assertThat(decision.kind()).isEqualTo(Kind.COMPENSATE);
        assertThat(decision.forwardResponse()).isEqualTo(found);
        assertThat(row(forward)).returns(LedgerState.COMMITTED, LedgerEntry::state)
                .returns("crm_9", LedgerEntry::externalRef);
    }

    @Test
    void lookupForwardThatUpdatedAPreExistingRecordNeedsAttentionInsteadOfBeingCompensated() {
        EffectSpec forward = forward(1, IdempotencyMode.LOOKUP, START_TO_CLOSE);
        leavePending(forward);
        advanceLeases(TimingContract.lease(START_TO_CLOSE));
        JsonNode found = mapper.createObjectNode().put("external_ref", "crm_9").put("created", false);

        CompensationDecision decision = reconciler.decide(TENANT, execution, NODE, 0,
                Reversibility.COMPENSATABLE, lookupReturning(Optional.of(found)));

        assertThat(decision.kind()).isEqualTo(Kind.NEEDS_ATTENTION);
        assertThat(row(forward).state()).isEqualTo(LedgerState.COMMITTED);
    }

    @Test
    void committedForwardThatCreatedItsRecordIsStillCompensated() {
        EffectSpec forward = forward(1, IdempotencyMode.LOOKUP, START_TO_CLOSE);
        guard.run(forward, key -> mapper.createObjectNode().put("external_ref", "crm_9").put("created", true));

        assertThat(decide(Reversibility.COMPENSATABLE).kind()).isEqualTo(Kind.COMPENSATE);
    }

    @Test
    void committedForwardThatUpdatedAPreExistingRecordNeedsAttention() {
        EffectSpec forward = forward(1, IdempotencyMode.LOOKUP, START_TO_CLOSE);
        guard.run(forward, key -> mapper.createObjectNode().put("external_ref", "crm_9").put("created", false));

        assertThat(decide(Reversibility.COMPENSATABLE).kind()).isEqualTo(Kind.NEEDS_ATTENTION);
    }

    @Test
    void lookupForwardNotFoundIsSkipped() {
        EffectSpec forward = forward(1, IdempotencyMode.LOOKUP, START_TO_CLOSE);
        leavePending(forward);
        advanceLeases(TimingContract.lease(START_TO_CLOSE));

        CompensationDecision decision = reconciler.decide(TENANT, execution, NODE, 0,
                Reversibility.COMPENSATABLE, lookupReturning(Optional.empty()));

        assertThat(decision.kind()).isEqualTo(Kind.SKIP);
    }

    @Test
    void lookupForwardWithoutALookupIsReconciledNotSkipped() {
        EffectSpec forward = forward(1, IdempotencyMode.LOOKUP, START_TO_CLOSE);
        leavePending(forward);
        advanceLeases(TimingContract.lease(START_TO_CLOSE));

        assertThat(decide(Reversibility.COMPENSATABLE).kind()).isEqualTo(Kind.RECONCILE_FORWARD);
    }

    private CompensationDecision decide(Reversibility reversibility) {
        return reconciler.decide(TENANT, execution, NODE, 0, reversibility);
    }

    /** A forward attempt whose call timed out: the row stays PENDING under the attempt's lease. */
    private void leavePending(EffectSpec forward) {
        assertThatThrownBy(() -> guard.run(forward, key -> {
            throw new RetryableError(ErrorCodes.UPSTREAM_TIMEOUT, "timeout");
        })).isInstanceOf(RetryableError.class);
        assertThat(row(forward).state()).isEqualTo(LedgerState.PENDING);
    }

    private static EffectCall lookupReturning(Optional<JsonNode> result) {
        return new EffectCall() {
            @Override
            public JsonNode invoke(String idempotencyKey) {
                throw new AssertionError("reconciler must not invoke the forward call");
            }

            @Override
            public Optional<JsonNode> lookup() {
                return result;
            }
        };
    }
}
