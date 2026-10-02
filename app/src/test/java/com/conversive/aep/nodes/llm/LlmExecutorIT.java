package com.conversive.aep.nodes.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.nodes.NodeResult;
import com.conversive.aep.router.LlmCallRecord;
import com.conversive.aep.router.RouteReasons;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@code llm} node → router → WireMock providers, with one {@code llm_call} row per provider call in Postgres. */
class LlmExecutorIT extends PostgresIntegrationTest {

    private static final TenantId TENANT = TenantId.of("t_llm_it");
    private static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        for (String p : List.of("llm-a", "llm-b", "vllm")) {
            registry.add("aep.router.providers." + p + ".base-url", () -> WIRE_MOCK.baseUrl() + "/llm/" + p);
        }
        registry.add("aep.outbound.allow-hosts", () -> "localhost:" + WIRE_MOCK.port());
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @Autowired
    private LlmExecutor executor;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void reset() {
        WIRE_MOCK.resetAll();
    }

    private static String completions(String provider) {
        return "/llm/" + provider + "/v1/chat/completions";
    }

    private static String reply(String content) {
        return """
                {"choices":[{"message":{"role":"assistant","content":%s},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":40,"completion_tokens":10,"total_tokens":50}}
                """.formatted(new ObjectMapper().valueToTree(content).toString());
    }

    private NodeContext context(ExecutionId executionId, String configJson) throws Exception {
        JsonNode config = mapper.readTree(configJson);
        JsonNode input = mapper.readTree("{\"lead\":{\"name\":\"Ada\",\"company\":\"Acme\"}}");
        return new NodeContext(TENANT, executionId, "wf_1", 1, "summarise", LlmExecutor.TYPE, 0, 1, Phase.FORWARD,
                ExecutionMode.LIVE, false, config, input, Duration.ofSeconds(30), Priority.NORMAL, null);
    }

    private List<Map<String, Object>> rows(ExecutionId executionId) {
        return jdbc.sql("""
                SELECT provider, reason, outcome, error_code, prompt_tokens, completion_tokens, cost_usd, seq, candidates::text AS candidates
                FROM llm_call WHERE tenant_id = :tenantId AND execution_id = :executionId ORDER BY seq
                """)
                .param("tenantId", TENANT.value())
                .param("executionId", executionId.value())
                .query()
                .listOfRows();
    }

    @Test
    void routesToTheBestProviderRendersThePromptAndRecordsTheCall() throws Exception {
        WIRE_MOCK.stubFor(post(urlEqualTo(completions("vllm"))).willReturn(okJson(reply("{\"tier\":\"gold\"}"))));
        ExecutionId executionId = ExecutionId.random();

        NodeResult result = executor.execute(context(executionId, """
                {"prompt":"Classify {{lead.name}} at {{lead.company}}","system":"Answer in JSON"}
                """));

        assertThat(result.output().path("provider").asText()).isEqualTo("vllm");
        assertThat(result.output().path("json").path("tier").asText()).isEqualTo("gold");
        assertThat(result.tokens()).isEqualTo(50);
        assertThat(result.costUsd()).isEqualByComparingTo("0.000100");
        assertThat(result.meta()).containsEntry("reason", RouteReasons.BEST_SCORE);
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo(completions("vllm")))
                .withRequestBody(containing("Classify Ada at Acme")));

        List<Map<String, Object>> rows = rows(executionId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("provider", "vllm").containsEntry("reason", RouteReasons.BEST_SCORE)
                .containsEntry("outcome", LlmCallRecord.SUCCEEDED).containsEntry("prompt_tokens", 40);
        assertThat((String) rows.get(0).get("candidates")).contains("llm-a", "llm-b", "vllm");
    }

    @Test
    void fallsBackToBWhenVllmReturns503AndRecordsBothCalls() throws Exception {
        WIRE_MOCK.stubFor(post(urlEqualTo(completions("vllm"))).willReturn(aResponse().withStatus(503)));
        WIRE_MOCK.stubFor(post(urlEqualTo(completions("llm-b"))).willReturn(okJson(reply("plain answer"))));
        ExecutionId executionId = ExecutionId.random();

        NodeResult result = executor.execute(context(executionId, "{\"prompt\":\"hi\"}"));

        assertThat(result.output().path("provider").asText()).isEqualTo("llm-b");
        assertThat(result.output().path("text").asText()).isEqualTo("plain answer");
        assertThat(result.output().has("json")).isFalse();
        List<Map<String, Object>> rows = rows(executionId);
        assertThat(rows).extracting(r -> r.get("provider")).containsExactly("vllm", "llm-b");
        assertThat(rows.get(0)).containsEntry("outcome", LlmCallRecord.FAILED)
                .containsEntry("error_code", ErrorCodes.UPSTREAM_UNAVAILABLE);
        assertThat(rows.get(1)).containsEntry("outcome", LlmCallRecord.SUCCEEDED)
                .containsEntry("reason", RouteReasons.FALLBACK_AFTER_ERROR);
    }

    @Test
    void failsRetryablyWhenEveryProviderFailsAndRecordsEveryCall() throws Exception {
        for (String p : List.of("llm-a", "llm-b", "vllm")) {
            WIRE_MOCK.stubFor(post(urlEqualTo(completions(p))).willReturn(aResponse().withStatus(500)));
        }
        ExecutionId executionId = ExecutionId.random();

        assertThatThrownBy(() -> executor.execute(context(executionId, "{\"prompt\":\"hi\",\"priority\":\"high\"}")))
                .isInstanceOfSatisfying(RetryableError.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.LLM_UNAVAILABLE));

        assertThat(rows(executionId)).extracting(r -> r.get("provider")).containsExactly("vllm", "llm-a", "llm-b");
    }
}
