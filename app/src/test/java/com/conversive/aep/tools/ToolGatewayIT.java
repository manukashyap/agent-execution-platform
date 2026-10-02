package com.conversive.aep.tools;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.common.http.UpstreamClientError;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@link ToolGateway} against WireMock emulating the mocks' JSON-RPC {@code /mcp} contract, with Postgres. */
class ToolGatewayIT extends PostgresIntegrationTest {

    static final String TEST_CREDENTIAL = "test-only-tool-credential-7f3a";
    private static final TenantId DEV = TenantId.of("t_dev");
    private static final TenantId OTHER = TenantId.of("t_other");
    private static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void mcp(DynamicPropertyRegistry registry) {
        registry.add("aep.tools.mcp-url", () -> WIRE_MOCK.baseUrl() + "/mcp");
        registry.add("aep.tools.dev-credential", () -> TEST_CREDENTIAL);
        registry.add("aep.outbound.allow-hosts", () -> "localhost:" + WIRE_MOCK.port());
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @Autowired
    private ToolGateway gateway;

    @Autowired
    private ToolCallAudit audit;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void reset() {
        WIRE_MOCK.resetAll();
    }

    static String toolResult(String json, boolean isError) {
        return """
                {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"json","json":%s}],"isError":%s}}
                """.formatted(json, isError);
    }

    static String rpcError(int code) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":" + code + ",\"message\":\"x\"}}";
    }

    private void stubMcp(String body) {
        WIRE_MOCK.stubFor(post(urlEqualTo("/mcp")).willReturn(okJson(body)));
    }

    private ToolInvocation invocation(TenantId tenant, ExecutionId exec, String tool, String argsJson, int attempt)
            throws Exception {
        return new ToolInvocation(tenant, exec, "n1", 0, attempt, null, null, tool, mapper.readTree(argsJson), null);
    }

    @Test
    void readOnlyToolRoundTripsOverJsonRpcWithTheCredentialOnlyInTheAuthorizationHeader() throws Exception {
        stubMcp(toolResult("{\"leads\":[{\"external_ref\":\"l1\"}]}", false));
        ExecutionId exec = ExecutionId.random();

        JsonNode result = gateway.invoke(invocation(DEV, exec, "leads.fetch", "{\"limit\":2}", 1));

        assertThat(result.path("leads").get(0).path("external_ref").asText()).isEqualTo("l1");
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/mcp"))
                .withHeader("Authorization", equalTo("Bearer " + TEST_CREDENTIAL))
                .withRequestBody(matchingJsonPath("$.jsonrpc", equalTo("2.0")))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
                .withRequestBody(matchingJsonPath("$.params.name", equalTo("leads.fetch")))
                .withRequestBody(matchingJsonPath("$.params.arguments.limit", equalTo("2"))));
        assertThat(WIRE_MOCK.getAllServeEvents().get(0).getRequest().getBodyAsString()).doesNotContain(TEST_CREDENTIAL);
        List<ToolCallAudit.Entry> rows = audit.findByExecution(DEV, exec);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.outcome()).isEqualTo(ToolCallAudit.SUCCEEDED);
            assertThat(row.argsSha256()).isEqualTo(ArgsDigest.sha256(mapper.readTree("{\"limit\":2}")));
            assertThat(row.effectKey()).isNull();
        });
    }

    @Test
    void invalidArgsAreNonRetryableAndNeverReachTheServer() throws Exception {
        ExecutionId exec = ExecutionId.random();

        assertThatThrownBy(() -> gateway.invoke(invocation(DEV, exec, "leads.fetch", "{\"limit\":500,\"x\":1}", 1)))
                .isInstanceOfSatisfying(NonRetryableError.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("limit").contains("x");
                });
        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
        assertThat(audit.findByExecution(DEV, exec)).singleElement()
                .satisfies(row -> assertThat(row.errorCode()).isEqualTo(ErrorCodes.VALIDATION_FAILED));
    }

    @Test
    void missingGrantIsToolForbidden() throws Exception {
        ExecutionId exec = ExecutionId.random();

        assertThatThrownBy(() -> gateway.invoke(invocation(OTHER, exec, "payments.charge",
                "{\"customer_id\":\"c1\",\"amount_cents\":100,\"currency\":\"USD\"}", 1)))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOOL_FORBIDDEN));
        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
        assertThat(audit.findByExecution(OTHER, exec)).singleElement()
                .satisfies(row -> assertThat(row.outcome()).isEqualTo(ToolCallAudit.FAILED));
    }

    @Test
    void unknownToolIsToolNotFound() throws Exception {
        assertThatThrownBy(() -> gateway.invoke(invocation(DEV, ExecutionId.random(), "nope.tool", "{}", 1)))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOOL_NOT_FOUND));
    }

    @Test
    void timeoutIsRetryableAndEveryAttemptIsAudited() throws Exception {
        WIRE_MOCK.stubFor(post(urlEqualTo("/mcp"))
                .willReturn(okJson(toolResult("{\"leads\":[]}", false)).withFixedDelay(1500)));
        ExecutionId exec = ExecutionId.random();
        ToolInvocation first = new ToolInvocation(DEV, exec, "n1", 0, 1, null, null, "leads.fetch",
                mapper.readTree("{}"), Duration.ofSeconds(2));

        assertThatThrownBy(() -> gateway.invoke(first)).isInstanceOfSatisfying(RetryableError.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_TIMEOUT));

        stubMcp(toolResult("{\"leads\":[]}", false));
        ToolInvocation retry = new ToolInvocation(DEV, exec, "n1", 0, 2, null, null, "leads.fetch",
                mapper.readTree("{}"), Duration.ofSeconds(2));
        assertThat(gateway.invoke(retry).path("leads").isArray()).isTrue();

        assertThat(audit.findByExecution(DEV, exec))
                .extracting(ToolCallAudit.Entry::attempt, ToolCallAudit.Entry::outcome, ToolCallAudit.Entry::errorCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, ToolCallAudit.FAILED, ErrorCodes.UPSTREAM_TIMEOUT),
                        org.assertj.core.groups.Tuple.tuple(2, ToolCallAudit.SUCCEEDED, null));
    }

    @Test
    void jsonRpcErrorsMapOntoTheTaxonomy() throws Exception {
        stubMcp(rpcError(-32602));
        assertThatThrownBy(() -> gateway.invoke(invocation(DEV, ExecutionId.random(), "crm.get",
                "{\"external_ref\":\"r\"}", 1))).isInstanceOfSatisfying(NonRetryableError.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED));

        stubMcp(rpcError(-32601));
        assertThatThrownBy(() -> gateway.invoke(invocation(DEV, ExecutionId.random(), "crm.get",
                "{\"external_ref\":\"r\"}", 1))).isInstanceOfSatisfying(NonRetryableError.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOOL_NOT_FOUND));

        stubMcp(toolResult("{\"error\":\"boom\",\"status\":503}", true));
        assertThatThrownBy(() -> gateway.invoke(invocation(DEV, ExecutionId.random(), "crm.get",
                "{\"external_ref\":\"r\"}", 1))).isInstanceOfSatisfying(RetryableError.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_UNAVAILABLE));

        stubMcp(toolResult("{\"error\":\"not_found\",\"status\":404}", true));
        assertThatThrownBy(() -> gateway.invoke(invocation(DEV, ExecutionId.random(), "crm.get",
                "{\"external_ref\":\"r\"}", 1))).isInstanceOfSatisfying(UpstreamClientError.class,
                e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void http429IsRetryableRateLimited() throws Exception {
        WIRE_MOCK.stubFor(post(urlEqualTo("/mcp")).willReturn(aResponse().withStatus(429)));

        assertThatThrownBy(() -> gateway.invoke(invocation(DEV, ExecutionId.random(), "leads.fetch", "{}", 1)))
                .isInstanceOfSatisfying(RetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_RATE_LIMITED));
    }

    @Test
    void chargeGoesThroughTheLedgerSoASecondInvokeWithTheSameIdentityDoesNotCallTheProvider() throws Exception {
        stubMcp(toolResult("{\"charge_id\":\"ch_1\",\"status\":\"succeeded\"}", false));
        ExecutionId exec = ExecutionId.random();
        String args = "{\"customer_id\":\"c1\",\"amount_cents\":100,\"currency\":\"USD\"}";

        JsonNode first = gateway.invoke(invocation(DEV, exec, "payments.charge", args, 1));
        JsonNode second = gateway.invoke(invocation(DEV, exec, "payments.charge", args, 2));

        assertThat(first.path("charge_id").asText()).isEqualTo("ch_1");
        assertThat(second).isEqualTo(first);
        assertThat(WIRE_MOCK.getAllServeEvents()).hasSize(1);
        String key = WIRE_MOCK.getAllServeEvents().get(0).getRequest().getHeader("Idempotency-Key");
        assertThat(audit.findByExecution(DEV, exec)).hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.outcome()).isEqualTo(ToolCallAudit.SUCCEEDED);
                    assertThat(row.effectKey()).isEqualTo(key).hasSize(64);
                });
    }

    @Test
    void inFlightToolAnswerIsAuditedAsInProgress() throws Exception {
        stubMcp(toolResult("{\"error\":\"in_progress\",\"status\":409}", true));
        ExecutionId exec = ExecutionId.random();

        assertThatThrownBy(() -> gateway.invoke(invocation(DEV, exec, "payments.charge",
                "{\"customer_id\":\"c1\",\"amount_cents\":100,\"currency\":\"USD\"}", 1)))
                .isInstanceOfSatisfying(RetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.EFFECT_IN_PROGRESS));
        assertThat(audit.findByExecution(DEV, exec)).singleElement()
                .satisfies(row -> assertThat(row.outcome()).isEqualTo(ToolCallAudit.IN_PROGRESS));
    }

    @Test
    void lookupToolFindsAnEarlierUpsertInsteadOfRepeatingIt() throws Exception {
        // First attempt times out (may have happened); the retry must look it up via crm.get rather than re-create.
        String args = "{\"external_ref\":\"lead_9\",\"name\":\"Ada\",\"email\":\"ada@example.com\"}";
        ExecutionId exec = ExecutionId.random();
        WIRE_MOCK.stubFor(post(urlEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.params.name", equalTo("crm.upsert")))
                .willReturn(okJson(toolResult("{\"external_ref\":\"lead_9\"}", false)).withFixedDelay(1500)));
        ToolInvocation first = new ToolInvocation(DEV, exec, "n1", 0, 1, null, null, "crm.upsert",
                mapper.readTree(args), Duration.ofSeconds(2));
        assertThatThrownBy(() -> gateway.invoke(first)).isInstanceOf(RetryableError.class);

        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(post(urlEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.params.name", equalTo("crm.get")))
                .willReturn(okJson(toolResult("{\"contacts\":[{\"external_ref\":\"lead_9\",\"id\":\"c_1\"}]}", false))));
        ToolInvocation retry = new ToolInvocation(DEV, exec, "n1", 0, 2, null, null, "crm.upsert",
                mapper.readTree(args), Duration.ofSeconds(2));
        JsonNode found = waitForLeaseThenInvoke(retry);

        assertThat(found.path("id").asText()).isEqualTo("c_1");
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/mcp"))
                .withRequestBody(matchingJsonPath("$.params.arguments.external_ref", equalTo("lead_9"))));
        assertThat(WIRE_MOCK.getAllServeEvents()).hasSize(1);
    }

    /** A timed-out attempt keeps its lease (stc + grace); retry until the lease expires, as Temporal would. */
    private JsonNode waitForLeaseThenInvoke(ToolInvocation retry) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            try {
                return gateway.invoke(retry);
            } catch (RetryableError e) {
                if (!ErrorCodes.EFFECT_IN_PROGRESS.equals(e.code()) || System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(250);
            }
        }
    }
}
