package com.conversive.aep.nodes.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.common.http.NonLiveEgress;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.nodes.NodeResult;
import com.conversive.aep.support.WireMockToolsIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** AI tool round: mock LLM (WireMock) asks for READ_ONLY tools, the gateway calls WireMock {@code /mcp}. */
class LlmToolRoundIT extends WireMockToolsIntegrationTest {

    private static final TenantId DEV = TenantId.of("t_dev");
    private static final String LLM_PATH = "/llm/(llm-a|llm-b)/v1/chat/completions";
    private static final String CONTACTS = "{\"contacts\":[{\"external_ref\":\"lead_7\",\"name\":\"Ada\"}]}";

    @Autowired
    private LlmExecutor executor;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper mapper;

    private static String toolCallReply(String tool, String args) {
        String quoted = new ObjectMapper().valueToTree(args).toString();
        return """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1",
                 "type":"function","function":{"name":"%s","arguments":%s}}]},"finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":30,"completion_tokens":5,"total_tokens":35}}
                """.formatted(tool, quoted);
    }

    private static String contentReply(String content) {
        return """
                {"choices":[{"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":60,"completion_tokens":10,"total_tokens":70}}
                """.formatted(content);
    }

    /** Before any tool message: request {@code tool}; once a tool result is present: answer with content. */
    private void stubLlm(String tool, String args) {
        WIRE_MOCK.stubFor(post(urlPathMatching(LLM_PATH)).atPriority(5)
                .willReturn(okJson(toolCallReply(tool, args))));
        WIRE_MOCK.stubFor(post(urlPathMatching(LLM_PATH)).atPriority(1)
                .withRequestBody(matchingJsonPath("$.messages[?(@.role == 'tool')]"))
                .willReturn(okJson(contentReply("final: Ada is a lead"))));
    }

    private void stubMcp(String json) {
        WIRE_MOCK.stubFor(post(urlEqualTo("/mcp")).willReturn(okJson(toolResult(json, false))));
    }

    private NodeContext context(ExecutionId exec, String config) throws Exception {
        return new NodeContext(DEV, exec, "wf_1", 1, "enrich", LlmExecutor.TYPE, 0, 1, Phase.FORWARD,
                ExecutionMode.LIVE, false, mapper.readTree(config), mapper.readTree("{\"ref\":\"lead_7\"}"),
                Duration.ofSeconds(30), Priority.NORMAL, null);
    }

    private List<ServeEvent> llmRequests() {
        return WIRE_MOCK.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().startsWith("/llm/"))
                .sorted((a, b) -> a.getRequest().getLoggedDate().compareTo(b.getRequest().getLoggedDate()))
                .toList();
    }

    private List<String> rowsAsText(String table, ExecutionId exec) {
        return jdbc.sql("SELECT row_to_json(t)::text FROM " + table
                        + " t WHERE tenant_id = :tenantId AND execution_id = :executionId")
                .param("tenantId", DEV.value())
                .param("executionId", exec.value())
                .query(String.class)
                .list();
    }

    @Test
    void modelCallsCrmGetAndTheFinalOutputCarriesTheToolResult() throws Exception {
        stubLlm("crm.get", "{\"external_ref\":\"lead_7\"}");
        stubMcp(CONTACTS);
        ExecutionId exec = ExecutionId.random();

        NodeResult result = executor.execute(context(exec,
                "{\"prompt\":\"Look up {{ref}}\",\"tools\":[\"crm.get\"]}"));

        assertThat(result.output().path("text").asText()).isEqualTo("final: Ada is a lead");
        assertThat(result.output().path("tool_results").get(0).path("tool").asText()).isEqualTo("crm.get");
        assertThat(result.output().path("tool_results").get(0).path("result")).isEqualTo(mapper.readTree(CONTACTS));
        assertThat(result.tokens()).isEqualTo(105);
        assertThat(result.meta()).containsEntry("turns", "2").containsEntry("tool_calls", "1");
        assertThat(jdbc.sql("SELECT turn FROM llm_call WHERE tenant_id = :t AND execution_id = :e ORDER BY turn")
                .param("t", DEV.value()).param("e", exec.value()).query(Integer.class).list()).containsExactly(0, 1);

        List<ServeEvent> llm = llmRequests();
        assertThat(llm).hasSize(2);
        String first = llm.get(0).getRequest().getBodyAsString();
        assertThat(first).contains("\"name\":\"crm.get\"").contains("untrusted").contains("Look up lead_7");
        String second = llm.get(1).getRequest().getBodyAsString();
        assertThat(mapper.readTree(second).path("messages").findValuesAsText("role")).contains("tool");
        assertThat(second).contains("\\\"untrusted\\\":true").contains("lead_7").contains("Ada");
        assertNoCredentialAnywhere(exec, result);
    }

    @Test
    void aDryRunWithAllowReadOnlyFalseNeverReachesTheRealTool() throws Exception {
        stubLlm("crm.get", "{\"external_ref\":\"lead_7\"}");
        stubMcp(CONTACTS);
        NodeContext live = context(ExecutionId.random(), "{\"prompt\":\"Look up {{ref}}\",\"tools\":[\"crm.get\"]}");
        NodeContext dry = new NodeContext(live.tenantId(), live.executionId(), live.workflowId(), live.defVersion(),
                live.nodeId(), live.nodeType(), live.callIndex(), live.attempt(), live.phase(), ExecutionMode.DRY_RUN,
                false, live.config(), live.input(), live.startToClose(), live.priority(),
                new com.conversive.aep.nodes.DryRunOptions(false, false));

        NodeResult result = NonLiveEgress.permit(() -> executor.execute(dry));

        assertThat(WIRE_MOCK.findAll(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlEqualTo("/mcp")))).isEmpty();
        assertThat(result.output().path("tool_results").get(0).path("result")).isNotEqualTo(mapper.readTree(CONTACTS));
        assertThat(result.output().path("tool_results").get(0).path("result").path("name").asText())
                .isEqualTo("Dry Run");
    }

    @Test
    void leadsFetchRoundWorksToo() throws Exception {
        stubLlm("leads.fetch", "{\"limit\":2}");
        stubMcp("{\"leads\":[{\"external_ref\":\"l1\"}]}");

        NodeResult result = executor.execute(context(ExecutionId.random(),
                "{\"prompt\":\"Fetch leads\",\"tools\":[\"leads.fetch\"]}"));

        assertThat(result.output().path("tool_results").get(0).path("result").path("leads").get(0)
                .path("external_ref").asText()).isEqualTo("l1");
    }

    @Test
    void aFourthRequestedToolCallIsToolCallLimit() throws Exception {
        // The model never stops asking for tools; maxToolCalls 3 allows three, the fourth fails the node.
        WIRE_MOCK.stubFor(post(urlPathMatching(LLM_PATH))
                .willReturn(okJson(toolCallReply("leads.fetch", "{\"limit\":1}"))));
        stubMcp("{\"leads\":[]}");

        assertThatThrownBy(() -> executor.execute(context(ExecutionId.random(),
                "{\"prompt\":\"loop\",\"tools\":[\"leads.fetch\"],\"maxToolCalls\":3}")))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOOL_CALL_LIMIT));
        assertThat(WIRE_MOCK.findAll(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlEqualTo("/mcp")))).hasSize(3);
        assertThat(llmRequests()).hasSize(4);
    }

    @Test
    void defaultLimitIsOneToolCall() throws Exception {
        WIRE_MOCK.stubFor(post(urlPathMatching(LLM_PATH))
                .willReturn(okJson(toolCallReply("leads.fetch", "{\"limit\":1}"))));
        stubMcp("{\"leads\":[]}");

        assertThatThrownBy(() -> executor.execute(context(ExecutionId.random(),
                "{\"prompt\":\"loop\",\"tools\":[\"leads.fetch\"]}")))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOOL_CALL_LIMIT));
    }

    @Test
    void modelAskingForPaymentsChargeIsToolForbiddenAndNothingIsCharged() throws Exception {
        stubLlm("payments.charge", "{\"customer_id\":\"c1\",\"amount_cents\":100,\"currency\":\"USD\"}");

        assertThatThrownBy(() -> executor.execute(context(ExecutionId.random(),
                "{\"prompt\":\"Charge them\",\"tools\":[\"crm.get\"]}")))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOOL_FORBIDDEN));
        assertThat(WIRE_MOCK.findAll(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlEqualTo("/mcp")))).isEmpty();
    }

    @Test
    void sideEffectingToolsCannotBeOfferedToTheModel() throws Exception {
        assertThatThrownBy(() -> executor.execute(context(ExecutionId.random(),
                "{\"prompt\":\"p\",\"tools\":[\"payments.charge\"]}")))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOOL_FORBIDDEN));
        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
    }

    @Test
    void maxToolCallsAboveTheCapIsInvalid() throws Exception {
        assertThatThrownBy(() -> executor.execute(context(ExecutionId.random(),
                "{\"prompt\":\"p\",\"tools\":[\"crm.get\"],\"maxToolCalls\":4}")))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED));
    }

    private void assertNoCredentialAnywhere(ExecutionId exec, NodeResult result) {
        assertThat(result.output().toString()).doesNotContain(TEST_CREDENTIAL);
        assertThat(result.meta().toString()).doesNotContain(TEST_CREDENTIAL);
        assertThat(rowsAsText("llm_call", exec)).isNotEmpty().allSatisfy(r -> assertThat(r).doesNotContain(TEST_CREDENTIAL));
        assertThat(rowsAsText("tool_call_audit", exec)).isNotEmpty()
                .allSatisfy(r -> assertThat(r).doesNotContain(TEST_CREDENTIAL));
        assertThat(WIRE_MOCK.getAllServeEvents()).allSatisfy(e -> assertThat(e.getRequest().getBodyAsString())
                .doesNotContain(TEST_CREDENTIAL));
        assertThat(llmRequests()).allSatisfy(e -> assertThat(String.valueOf(e.getRequest().getHeader("Authorization")))
                .doesNotContain(TEST_CREDENTIAL));
    }
}
