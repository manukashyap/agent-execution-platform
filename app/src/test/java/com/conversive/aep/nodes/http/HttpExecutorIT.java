package com.conversive.aep.nodes.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.common.http.OutboundClient;
import com.conversive.aep.common.http.OutboundProperties;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.nodes.NodeResult;
import com.conversive.aep.sideeffect.SideEffectGuard;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The http node against WireMock: rendering, the error-mapping table and the guarded side-effecting path. */
class HttpExecutorIT extends PostgresIntegrationTest {

    private static WireMockServer wireMock;

    @Autowired
    SideEffectGuard guard;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcClient jdbc;

    private HttpExecutor executor;
    private TenantId tenant;

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
        OutboundProperties props = new OutboundProperties(List.of("localhost:" + wireMock.port()), List.of(),
                Duration.ofSeconds(1));
        executor = new HttpExecutor(new OutboundClient(props, mapper, Clock.systemUTC()), guard, mapper);
        tenant = new TestTenants(jdbc).create("t_http");
    }

    @Test
    void rendersUrlHeadersAndBodyFromTheInputAndReturnsStatusAndBody() throws Exception {
        wireMock.stubFor(any(urlPathEqualTo("/leads/42")).willReturn(okJson("{\"ok\":true}")));
        JsonNode config = mapper.readTree("""
                {"url":"%s/leads/{{fetch.id}}","method":"post","headers":{"X-Seg":"{{fetch.segment}}"},
                 "body":{"name":"{{fetch.name}}","fixed":1,"tags":["{{fetch.segment}}"]}}
                """.formatted(wireMock.baseUrl()));
        JsonNode input = mapper.readTree("{\"fetch\":{\"id\":42,\"segment\":\"smb\",\"name\":\"Ada\"}}");

        NodeResult result = executor.execute(ctx(config, input, false));

        assertThat(result.output().path("status").asInt()).isEqualTo(200);
        assertThat(result.output().path("body").path("ok").asBoolean()).isTrue();
        wireMock.verify(postRequestedFor(urlEqualTo("/leads/42")).withHeader("X-Seg", equalTo("smb"))
                .withRequestBody(equalToJson("{\"name\":\"Ada\",\"fixed\":1,\"tags\":[\"smb\"]}")));
    }

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource({
            "429, retryable,     UPSTREAM_RATE_LIMITED",
            "500, retryable,     UPSTREAM_UNAVAILABLE",
            "502, retryable,     UPSTREAM_UNAVAILABLE",
            "503, retryable,     UPSTREAM_UNAVAILABLE",
            "400, non-retryable, UPSTREAM_CLIENT_ERROR",
            "401, non-retryable, UPSTREAM_CLIENT_ERROR",
            "404, non-retryable, UPSTREAM_CLIENT_ERROR",
            "422, non-retryable, UPSTREAM_CLIENT_ERROR"})
    void mapsUpstreamStatusesToTheErrorTaxonomy(int status, String kind, String code) throws Exception {
        wireMock.stubFor(any(urlEqualTo("/x")).willReturn(aResponse().withStatus(status)));
        NodeContext ctx = ctx(mapper.readTree("{\"url\":\"" + wireMock.baseUrl() + "/x\"}"), mapper.readTree("{}"),
                false);

        Class<? extends RuntimeException> type = kind.equals("retryable") ? RetryableError.class
                : NonRetryableError.class;
        assertThatThrownBy(() -> executor.execute(ctx)).isInstanceOf(type)
                .satisfies(e -> assertThat(code(e)).isEqualTo(code));
    }

    @Test
    void aSlowUpstreamIsARetryableTimeoutAndAMissingUrlIsAValidationFailure() throws Exception {
        wireMock.stubFor(any(urlEqualTo("/slow")).willReturn(okJson("{}").withFixedDelay(2500)));
        NodeContext slow = new NodeContext(tenant, ExecutionId.random(), "wf", 1, "n", "http", 0, 1, Phase.FORWARD,
                ExecutionMode.LIVE, false, mapper.readTree("{\"url\":\"" + wireMock.baseUrl() + "/slow\"}"),
                mapper.readTree("{}"), Duration.ofSeconds(2), Priority.NORMAL, null);
        assertThatThrownBy(() -> executor.execute(slow)).isInstanceOf(RetryableError.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCodes.UPSTREAM_TIMEOUT));

        NodeContext noUrl = ctx(mapper.readTree("{}"), mapper.readTree("{}"), false);
        assertThatThrownBy(() -> executor.execute(noUrl)).isInstanceOf(NonRetryableError.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCodes.VALIDATION_FAILED));
    }

    @Test
    void sideEffectingCallsSendTheLedgerEffectKeyAndAreNotRepeated() throws Exception {
        wireMock.stubFor(any(urlEqualTo("/charge")).willReturn(okJson("{\"id\":\"ch_1\"}")));
        JsonNode config = mapper.readTree("""
                {"url":"%s/charge","method":"POST","headers":{"Idempotency-Key":"spoofed"},"body":{"amount":5}}
                """.formatted(wireMock.baseUrl()));
        NodeContext ctx = ctx(config, mapper.readTree("{}"), true);

        NodeResult first = executor.execute(ctx);
        NodeResult replay = executor.execute(ctx);

        assertThat(first.output().path("body").path("id").asText()).isEqualTo("ch_1");
        assertThat(replay.output()).isEqualTo(first.output());
        List<LoggedRequest> sent = wireMock.findAll(postRequestedFor(urlEqualTo("/charge")));
        assertThat(sent).hasSize(1);
        String key = sent.get(0).getHeader("Idempotency-Key");
        assertThat(key).isNotEqualTo("spoofed").matches("[0-9a-f]{64}");
        assertThat(jdbc.sql("SELECT count(*) FROM side_effect_ledger WHERE tenant_id = :t AND effect_key = :k")
                .param("t", tenant.value()).param("k", key).query(Long.class).single()).isEqualTo(1);
        wireMock.verify(postRequestedFor(urlEqualTo("/charge")).withHeader("Idempotency-Key", matching(".+")));
    }

    private NodeContext ctx(JsonNode config, JsonNode input, boolean sideEffecting) {
        return new NodeContext(tenant, ExecutionId.random(), "wf", 1, "n", "http", 0, 1, Phase.FORWARD,
                ExecutionMode.LIVE, sideEffecting, config, input, Duration.ofSeconds(5), Priority.NORMAL, null);
    }

    private static String code(Throwable e) {
        return e instanceof RetryableError r ? r.code() : ((NonRetryableError) e).code();
    }
}
