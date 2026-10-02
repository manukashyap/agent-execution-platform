package com.conversive.aep.sideeffect;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.RetryableError;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 06 §4.9 tests 1–3 (guard level) and the concurrent-duplicates proof. */
class ExactlyOnceScenariosIT extends LedgerTestSupport {

    /** Scaled-down PDF §9: StartToClose 2 s → client timeout 1 s, lease 7 s; the provider answers at 1.5 s. */
    private static final Duration START_TO_CLOSE = Duration.ofSeconds(2);
    private static final Duration PROVIDER_LATENCY = Duration.ofMillis(1500);
    private static final Duration LEASE = TimingContract.lease(START_TO_CLOSE);

    /** Simulates the worker dying: an Error, so no guard code runs after the provider call returned. */
    private static final class WorkerCrash extends Error {
    }

    @BeforeEach
    void chargeStub() {
        wireMock.stubFor(post("/payments/charge").willReturn(aResponse().withTransformers(IdempotentProviderStub.NAME)));
    }

    @Test
    void crashBetweenCallAndCommitIsRecoveredByNativeKeyRecallWithoutASecondCharge() {
        EffectSpec attempt1 = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        EffectCall crashAfterCall = key -> {
            charge(attempt1).invoke(key);
            throw new WorkerCrash();
        };
        assertThatThrownBy(() -> guard.run(attempt1, crashAfterCall)).isInstanceOf(WorkerCrash.class);
        assertThat(row(attempt1).state()).isEqualTo(LedgerState.PENDING);

        clock.advance(LEASE);
        EffectSpec attempt2 = forward(2, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        JsonNode result = guard.run(attempt2, charge(attempt2));

        assertThat(result.get("charge_id").asText()).isEqualTo("ch_1");
        assertThat(PROVIDER.performedEffects()).isEqualTo(1);
        assertThat(row(attempt2)).returns(LedgerState.COMMITTED, LedgerEntry::state)
                .returns("ch_1", LedgerEntry::externalRef)
                .returns(2, LedgerEntry::ownerAttempt);
        wireMock.verify(exactly(2), postRequestedFor(urlEqualTo("/payments/charge"))
                .withHeader("Idempotency-Key", equalTo(attempt1.key().value())));
    }

    @Test
    void pdfTimingNativeKeyDelaysRetryToLeaseEndThenRecallReturnsTheStoredChargeExactlyOnce() throws Exception {
        PROVIDER.reset(PROVIDER_LATENCY);
        EffectSpec attempt1 = forward(1, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        assertThatThrownBy(() -> guard.run(attempt1, charge(attempt1)))
                .isInstanceOfSatisfying(RetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_TIMEOUT));

        clock.advance(START_TO_CLOSE);
        EffectSpec attempt2 = forward(2, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        assertThatThrownBy(() -> guard.run(attempt2, charge(attempt2)))
                .isInstanceOfSatisfying(RetryableError.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCodes.EFFECT_IN_PROGRESS);
                    assertThat(e.nextRetryDelay()).isEqualTo(LEASE.minus(START_TO_CLOSE));
                });
        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo("/payments/charge")));

        PROVIDER.awaitCompleted(attempt1.key().value(), Duration.ofSeconds(5));
        clock.set(row(attempt1).leaseUntil());
        EffectSpec attempt3 = forward(3, IdempotencyMode.NATIVE_KEY, START_TO_CLOSE);
        JsonNode result = guard.run(attempt3, charge(attempt3));

        assertThat(result.get("charge_id").asText()).isEqualTo("ch_1");
        assertThat(PROVIDER.performedEffects()).isEqualTo(1);
        assertThat(row(attempt3).state()).isEqualTo(LedgerState.COMMITTED);
        wireMock.verify(exactly(2), postRequestedFor(urlEqualTo("/payments/charge"))
                .withHeader("Idempotency-Key", equalTo(attempt1.key().value())));
    }

    @Test
    void pdfTimingNoneModeNeverCallsTwiceAndEscalatesToNeedsAttentionWithRowUnknown() {
        wireMock.stubFor(post("/messages/send").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"message_id\":\"m_1\"}")
                .withFixedDelay((int) PROVIDER_LATENCY.toMillis())));
        JsonNode body = mapper.createObjectNode().put("to", "+15550001").put("body", "hi");
        EffectSpec attempt1 = forward(1, IdempotencyMode.NONE, START_TO_CLOSE);
        assertThatThrownBy(() -> guard.run(attempt1, httpPost("/messages/send", attempt1, body)))
                .isInstanceOf(RetryableError.class);

        clock.advance(START_TO_CLOSE);
        EffectSpec attempt2 = forward(2, IdempotencyMode.NONE, START_TO_CLOSE);
        assertThatThrownBy(() -> guard.run(attempt2, httpPost("/messages/send", attempt2, body)))
                .isInstanceOfSatisfying(RetryableError.class,
                        e -> assertThat(e.nextRetryDelay()).isEqualTo(LEASE.minus(START_TO_CLOSE)));

        clock.set(row(attempt1).leaseUntil());
        for (int attempt = 3; attempt <= 4; attempt++) {
            EffectSpec next = forward(attempt, IdempotencyMode.NONE, START_TO_CLOSE);
            assertThatThrownBy(() -> guard.run(next, httpPost("/messages/send", next, body)))
                    .isInstanceOfSatisfying(NonRetryableError.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCodes.NEEDS_ATTENTION));
        }

        assertThat(row(attempt1).state()).isEqualTo(LedgerState.UNKNOWN);
        assertThat(meters.get(com.conversive.aep.observability.AepMetrics.SIDE_EFFECT_UNKNOWN).counter().count())
                .as("counted once, on the PENDING to UNKNOWN transition").isEqualTo(1);
        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo("/messages/send")));
    }

    @Test
    void tenConcurrentRunsOfTheSameEffectMakeExactlyOneProviderCall() throws Exception {
        PROVIDER.reset(Duration.ofMillis(300));
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                EffectSpec spec = forward(1, IdempotencyMode.NATIVE_KEY, Duration.ofSeconds(10));
                outcomes.add(pool.submit(() -> {
                    start.await();
                    try {
                        return guard.run(spec, charge(spec));
                    } catch (RetryableError e) {
                        return e.code();
                    }
                }));
            }
            start.countDown();
        }

        List<Object> results = new ArrayList<>();
        for (Future<Object> outcome : outcomes) {
            results.add(outcome.get());
        }
        assertThat(PROVIDER.performedEffects()).isEqualTo(1);
        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo("/payments/charge")));
        assertThat(results).filteredOn(JsonNode.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(r -> !(r instanceof JsonNode)).containsOnly(ErrorCodes.EFFECT_IN_PROGRESS);
    }
}
