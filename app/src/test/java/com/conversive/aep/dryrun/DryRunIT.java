package com.conversive.aep.dryrun;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.conversive.aep.api.auth.RequiresScope;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.engine.EngineHarness;
import com.conversive.aep.execution.ExecutionStatus;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.service.ExecutionService;
import com.conversive.aep.execution.service.StartCommand;
import com.conversive.aep.nodes.DryRunOptions;
import com.conversive.aep.support.InProcessTemporal;
import com.conversive.aep.support.TestTenants;
import com.conversive.aep.support.WireMockToolsIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** T6.3: the PDF §7 lead workflow and an unclassified POST in DRY_RUN, through the real executors. */
@AutoConfigureMockMvc
@Import(InProcessTemporal.class)
class DryRunIT extends WireMockToolsIntegrationTest {

    private static final String LLM_REPLY = """
            {"choices":[{"message":{"role":"assistant","content":"hot"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":20,"completion_tokens":1,"total_tokens":21}}
            """;
    private static final String LEADS = "{\"leads\":[{\"id\":\"l_1\",\"email\":\"a@example.com\"}]}";

    @DynamicPropertySource
    static void realExecutors(DynamicPropertyRegistry registry) {
        registry.add("test.real-node-types", () -> "http,llm,mcp");
    }

    @Autowired
    DefinitionService definitions;
    @Autowired
    ExecutionService executions;
    @Autowired
    ExecutionRepository repository;
    @Autowired
    WorkflowClient client;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    MockMvc mvc;

    private EngineHarness h;
    private String key;

    @BeforeEach
    void setUp() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/leads")).willReturn(okJson(LEADS)));
        WIRE_MOCK.stubFor(post(urlPathMatching("/llm/.*")).willReturn(okJson(LLM_REPLY)));
        WIRE_MOCK.stubFor(any(urlPathMatching("/(mcp|crm).*")).willReturn(okJson("{}")));
        TestTenants tenants = new TestTenants(jdbc);
        h = new EngineHarness(tenants.create("t_dry_" + UUID.randomUUID().toString().substring(0, 8)),
                definitions, executions, repository, client, jdbc, mapper);
        key = tenants.apiKey(h.tenant(), RequiresScope.EXECUTIONS_READ);
    }

    @Test
    void leadWorkflowReadsLiveAndMocksTheCrmWriteAndTheSend() throws Exception {
        h.publish(leadWorkflow());

        ExecutionId id = dryRun("lead_enrichment", new DryRunOptions(false, true));

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        WIRE_MOCK.verify(1, getRequestedFor(urlPathEqualTo("/leads")));
        WIRE_MOCK.verify(1, postRequestedFor(urlPathMatching("/llm/.*")));
        WIRE_MOCK.verify(0, anyRequestedFor(urlPathMatching("/mcp.*")));
        assertThat(ledgerRows(id)).isZero();
        JsonNode preview = preview(id).path("data");
        assertThat(targets(preview)).containsExactly("crm.upsert", "messaging.send");
        assertThat(preview.path("nodes").get(0).path("calls").get(0).path("mocked").asBoolean()).isFalse();
        assertThat(preview.path("nodes").get(0).path("calls").get(0).path("output").path("body"))
                .isEqualTo(mapper.readTree(LEADS));
        assertThat(preview.path("nodes").get(2).path("calls").get(0).path("mocked").asBoolean()).isTrue();
        assertThat(preview.path("compensationPlan").toString())
                .contains("\"nodeId\":\"send_message\",\"callIndex\":0,\"action\":\"STOP_AT_PIVOT\"")
                .contains("\"nodeId\":\"update_crm\",\"callIndex\":0,\"action\":\"NOT_COMPENSATED\"");
    }

    @Test
    void unclassifiedHttpPostIsMockedWithZeroRealHits() throws Exception {
        h.publish("""
                {"workflow_id":"post_only","version":1,"nodes":[
                  {"id":"push","type":"http","config":{"url":"%s/crm/contacts","method":"POST",
                   "body":{"email":"{{input.email}}"}}}]}
                """.formatted(WIRE_MOCK.baseUrl()));

        ExecutionId id = dryRun("post_only", DryRunOptions.defaults());

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        WIRE_MOCK.verify(0, anyRequestedFor(anyUrl()));
        JsonNode preview = preview(id).path("data");
        assertThat(targets(preview)).containsExactly("POST " + WIRE_MOCK.baseUrl() + "/crm/contacts");
        assertThat(preview.path("mockedCalls").get(0).path("nodeType").asText()).isEqualTo("http");
    }

    @Test
    void twoMockLlmRunsProduceByteIdenticalPreviews() throws Exception {
        h.publish(leadWorkflow());
        DryRunOptions mockLlm = new DryRunOptions(true, true);

        ExecutionId first = dryRun("lead_enrichment", mockLlm);
        ExecutionId second = dryRun("lead_enrichment", mockLlm);
        h.await(first);
        h.await(second);

        WIRE_MOCK.verify(0, postRequestedFor(urlPathMatching("/llm/.*")));
        String a = previewBody(first);
        assertThat(a).isEqualTo(previewBody(second)).contains("[dry-run mock completion ");
        assertThat(a).doesNotContain(first.value().toString());
    }

    private String leadWorkflow() {
        return """
                {"workflow_id":"lead_enrichment","version":1,"nodes":[
                  {"id":"fetch_leads","type":"http","side_effecting":false,
                   "config":{"url":"%s/leads","method":"GET"}},
                  {"id":"classify_leads","type":"llm",
                   "config":{"prompt":"Classify each lead as hot, warm or cold: {{fetch_leads.body}}"}},
                  {"id":"update_crm","type":"mcp","config":{"tool":"crm.upsert"},
                   "compensate":{"type":"mcp","config":{"tool":"crm.delete"}}},
                  {"id":"send_message","type":"mcp","config":{"tool":"messaging.send"}}]}
                """.formatted(WIRE_MOCK.baseUrl());
    }

    private ExecutionId dryRun(String workflowId, DryRunOptions options) {
        StartCommand cmd = new StartCommand(workflowId, null, h.json("{\"segment\":\"smb\"}"), ExecutionMode.DRY_RUN,
                options, null, UUID.randomUUID().toString());
        return executions.start(h.tenant(), cmd).execution().id();
    }

    private JsonNode preview(ExecutionId id) throws Exception {
        return mapper.readTree(previewBody(id));
    }

    private String previewBody(ExecutionId id) throws Exception {
        return mvc.perform(request(HttpMethod.GET, "/v1/executions/" + id.value() + "/preview")
                        .header("Authorization", "Bearer " + key))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static List<String> targets(JsonNode preview) {
        return preview.path("mockedCalls").findValuesAsText("target");
    }

    private int ledgerRows(ExecutionId id) {
        return jdbc.sql("SELECT count(*) FROM side_effect_ledger WHERE tenant_id = :t AND execution_id = :e")
                .param("t", h.tenant().value()).param("e", id.value())
                .query(Integer.class).single();
    }
}
