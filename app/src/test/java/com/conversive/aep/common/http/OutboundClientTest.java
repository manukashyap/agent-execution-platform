package com.conversive.aep.common.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.RetryableError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboundClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static WireMockServer wireMock;

    private OutboundClient client;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        OutboundProperties properties = new OutboundProperties(
                List.of("localhost:" + wireMock.port()), List.of("api.aep.test", "localhost:8000"), Duration.ofSeconds(1));
        client = new OutboundClient(properties, MAPPER, Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void refusesCloudMetadataAddress() {
        assertDenied(live("http://169.254.169.254/latest/meta-data/"), "non-public");
    }

    @Test
    void refusesPrivateTenSlashEight() {
        assertDenied(live("http://10.0.0.5/internal"), "non-public");
    }

    @Test
    void refusesLoopbackThatIsNotAllowListed() {
        assertDenied(live("http://127.0.0.1:9/"), "non-public");
    }

    @Test
    void refusesThePlatformsOwnHost() {
        assertDenied(live("http://api.aep.test/v1/workflows"), "own host");
        assertDenied(live("http://localhost:8000/v1/executions"), "own host");
    }

    @Test
    void refusesNonAllowListedHostOutsideLiveMode() {
        OutboundRequest dryRun = OutboundRequest.get(URI.create("https://api.example.com/x"), TIMEOUT, ExecutionMode.DRY_RUN);

        assertDenied(dryRun, "in DRY_RUN mode");
    }

    @Test
    void refusesEvenAnAllowListedHostOutsideLiveModeWithoutThePolicysPermit() {
        wireMock.stubFor(get("/ok").willReturn(okJson("{\"a\":1}")));

        assertDenied(OutboundRequest.get(uri("/ok"), TIMEOUT, ExecutionMode.DRY_RUN), "dry-run policy");
        assertDenied(OutboundRequest.get(uri("/ok"), TIMEOUT, ExecutionMode.REPLAY), "dry-run policy");
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void thePermitOnlyCoversCallsInsideIt() {
        wireMock.stubFor(get("/ok").willReturn(okJson("{\"a\":1}")));
        OutboundRequest dryRun = OutboundRequest.get(uri("/ok"), TIMEOUT, ExecutionMode.DRY_RUN);

        OutboundResponse inside = NonLiveEgress.permit(() -> client.send(dryRun));

        assertThat(inside.status()).isEqualTo(200);
        assertThat(NonLiveEgress.permitted()).isFalse();
        assertDenied(dryRun, "dry-run policy");
    }

    @Test
    void dryRunPolicyCanAllowACallButSsrfRulesStillApply() {
        OutboundRequest allowed = OutboundRequest.get(URI.create("http://10.1.2.3/x"), TIMEOUT, ExecutionMode.DRY_RUN)
                .withAllowInNonLive(true);

        assertDenied(allowed, "non-public");
    }

    @Test
    void refusesNonHttpSchemes() {
        assertDenied(live("file:///etc/passwd"), "scheme");
    }

    @Test
    void allowListedHostWorksInDryRunWhenThePolicyAllowsItAndParsesJson() {
        wireMock.stubFor(get("/ok").willReturn(okJson("{\"a\":1}")));

        OutboundResponse response = client.send(OutboundRequest.get(uri("/ok"), TIMEOUT, ExecutionMode.DRY_RUN)
                .withAllowInNonLive(true));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("a").asInt()).isEqualTo(1);
    }

    @Test
    void sendsIdempotencyKeyAndJsonBody() {
        wireMock.stubFor(post("/charge").willReturn(okJson("{\"id\":\"ch_1\"}")));

        client.send(OutboundRequest.post(uri("/charge"), MAPPER.createObjectNode().put("amount", 5), TIMEOUT,
                ExecutionMode.LIVE).withIdempotencyKey("abc123"));

        wireMock.verify(postRequestedFor(urlEqualTo("/charge"))
                .withHeader(OutboundClient.IDEMPOTENCY_KEY, equalTo("abc123"))
                .withHeader("Content-Type", equalTo("application/json")));
    }

    @Test
    void omitsIdempotencyKeyWhenNotGiven() {
        wireMock.stubFor(get("/ok").willReturn(okJson("{}")));

        client.send(OutboundRequest.get(uri("/ok"), TIMEOUT, ExecutionMode.LIVE));

        wireMock.verify(getRequestedFor(urlEqualTo("/ok")).withoutHeader(OutboundClient.IDEMPOTENCY_KEY));
    }

    @Test
    void timeoutIsRetryable() {
        wireMock.stubFor(get("/slow").willReturn(okJson("{}").withFixedDelay(1500)));

        assertThatThrownBy(() -> client.send(OutboundRequest.get(uri("/slow"), Duration.ofMillis(200), ExecutionMode.LIVE)))
                .isInstanceOfSatisfying(RetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_TIMEOUT));
    }

    @Test
    void bodyOverTheCapIsRejectedAsNonRetryableWhetherOrNotLengthIsAnnounced() {
        OutboundClient capped = new OutboundClient(new OutboundProperties(List.of("localhost:" + wireMock.port()),
                List.of(), Duration.ofSeconds(1)).withMaxResponseBytes(1000), MAPPER, Clock.systemUTC());
        String big = "{\"a\":\"" + "x".repeat(5000) + "\"}";
        wireMock.stubFor(get("/big").willReturn(okJson(big)));
        wireMock.stubFor(get("/big-chunked").willReturn(okJson(big).withChunkedDribbleDelay(5, 10)));

        for (String path : List.of("/big", "/big-chunked")) {
            assertThatThrownBy(() -> capped.send(OutboundRequest.get(uri(path), TIMEOUT, ExecutionMode.LIVE)))
                    .isInstanceOfSatisfying(NonRetryableError.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCodes.RESPONSE_TOO_LARGE));
        }
    }

    @Test
    void bodyUnderTheCapIsReadNormally() {
        OutboundClient capped = new OutboundClient(new OutboundProperties(List.of("localhost:" + wireMock.port()),
                List.of(), Duration.ofSeconds(1)).withMaxResponseBytes(1000), MAPPER, Clock.systemUTC());
        wireMock.stubFor(get("/small").willReturn(okJson("{\"a\":1}")));

        assertThat(capped.send(OutboundRequest.get(uri("/small"), TIMEOUT, ExecutionMode.LIVE)).body().get("a").asInt())
                .isEqualTo(1);
    }

    @Test
    void serverErrorIsRetryable() {
        wireMock.stubFor(get("/down").willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.send(OutboundRequest.get(uri("/down"), TIMEOUT, ExecutionMode.LIVE)))
                .isInstanceOfSatisfying(RetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_UNAVAILABLE));
    }

    @Test
    void tooManyRequestsCarriesRetryAfterAsNextRetryDelay() {
        wireMock.stubFor(get("/limited").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "3")));

        assertThatThrownBy(() -> client.send(OutboundRequest.get(uri("/limited"), TIMEOUT, ExecutionMode.LIVE)))
                .isInstanceOfSatisfying(RetryableError.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_RATE_LIMITED);
                    assertThat(e.nextRetryDelay()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    void retryAfterHttpDateIsRelativeToTheClock() {
        wireMock.stubFor(get("/limited").willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", "Fri, 02 Oct 2026 00:00:10 GMT")));

        assertThatThrownBy(() -> client.send(OutboundRequest.get(uri("/limited"), TIMEOUT, ExecutionMode.LIVE)))
                .isInstanceOfSatisfying(RetryableError.class,
                        e -> assertThat(e.nextRetryDelay()).isEqualTo(Duration.ofSeconds(10)));
    }

    @Test
    void clientErrorIsNonRetryable() {
        wireMock.stubFor(get("/missing").willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.send(OutboundRequest.get(uri("/missing"), TIMEOUT, ExecutionMode.LIVE)))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_CLIENT_ERROR));
    }

    @Test
    void clientErrorKeepsStatusAndBodySoCallersCanReinterpretA409() {
        wireMock.stubFor(get("/busy").willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"in_progress\"}")));

        assertThatThrownBy(() -> client.send(OutboundRequest.get(uri("/busy"), TIMEOUT, ExecutionMode.LIVE)))
                .isInstanceOfSatisfying(UpstreamClientError.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_CLIENT_ERROR);
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.body().get("error").asText()).isEqualTo("in_progress");
                });
    }

    @Test
    void doesNotFollowRedirects() {
        wireMock.stubFor(get("/redirect").willReturn(aResponse().withStatus(302)
                .withHeader("Location", "http://169.254.169.254/")));

        OutboundResponse response = client.send(OutboundRequest.get(uri("/redirect"), TIMEOUT, ExecutionMode.LIVE));

        assertThat(response.status()).isEqualTo(302);
    }

    private OutboundRequest live(String url) {
        return OutboundRequest.get(URI.create(url), TIMEOUT, ExecutionMode.LIVE);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + wireMock.port() + path);
    }

    private void assertDenied(OutboundRequest request, String reason) {
        assertThatThrownBy(() -> client.send(request))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.EGRESS_DENIED))
                .hasMessageContaining(reason);
    }
}
