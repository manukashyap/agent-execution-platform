package com.conversive.aep.common.http;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.RetryableError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pool sizing and pool-wait mapping: one route must carry the whole activity fan-out. */
class OutboundClientPoolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int CALLS = 20;
    private static final int DELAY_MS = 400;
    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort().containerThreads(64));
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void moreThanFiveConcurrentCallsToOneRouteProceedConcurrently() throws Exception {
        wireMock.stubFor(get("/slow").willReturn(okJson("{}").withFixedDelay(DELAY_MS)));
        OutboundClient client = client(new OutboundProperties(List.of("localhost:" + wireMock.port()), List.of(),
                Duration.ofSeconds(1)));

        long elapsedMs = timeConcurrentCalls(client);

        // Five connections per route would need CALLS / 5 = 4 sequential rounds (>= 1.6 s).
        assertThat(elapsedMs).isLessThan(DELAY_MS * 3L);
    }

    @Test
    void exhaustedPoolFailsAsNotSentBeforeAnythingIsTransmitted() throws Exception {
        wireMock.stubFor(get("/slow").willReturn(okJson("{}").withFixedDelay(1500)));
        OutboundClient client = client(new OutboundProperties(List.of("localhost:" + wireMock.port()), List.of(),
                Duration.ofSeconds(1), 10, 1, Duration.ofMillis(200), null));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<?> occupying = CompletableFuture.runAsync(() -> client.send(request()), pool);
            Thread.sleep(300);

            assertThatThrownBy(() -> client.send(request()))
                    .isInstanceOfSatisfying(RetryableError.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_NOT_SENT));
            occupying.get();
            assertThat(wireMock.getAllServeEvents()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static long timeConcurrentCalls(OutboundClient client) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CALLS);
        try {
            long start = System.nanoTime();
            List<CompletableFuture<?>> calls = new ArrayList<>();
            for (int i = 0; i < CALLS; i++) {
                calls.add(CompletableFuture.runAsync(() -> client.send(request()), pool));
            }
            CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get();
            return Duration.ofNanos(System.nanoTime() - start).toMillis();
        } finally {
            pool.shutdownNow();
        }
    }

    private static OutboundClient client(OutboundProperties properties) {
        return new OutboundClient(properties, MAPPER, Clock.systemUTC());
    }

    private static OutboundRequest request() {
        return OutboundRequest.get(URI.create("http://localhost:" + wireMock.port() + "/slow"), TIMEOUT, ExecutionMode.LIVE);
    }
}
