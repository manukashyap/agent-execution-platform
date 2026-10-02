package com.conversive.aep.observability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.conversive.aep.api.auth.RequiresScope;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.support.Fixtures;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * T8.2: {@code GET /v1/executions/{id}/trace} over seeded rows: a node retried once, an LLM call that fell back
 * from vllm to llm-b, two tool calls with ledger rows and the budget reservations behind the LLM calls.
 */
@AutoConfigureMockMvc
class TraceApiIT extends PostgresIntegrationTest {

    private static final Path SAMPLE = Path.of("build", "samples", "trace-sample.json");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;

    private TraceSeed seed;
    private String key;
    private String otherKey;

    @BeforeEach
    void seeded() {
        TestTenants tenants = new TestTenants(jdbc);
        TenantId tenant = tenants.create("t_trace");
        TenantId other = tenants.create("t_trace_other");
        key = tenants.apiKey(tenant, RequiresScope.EXECUTIONS_READ);
        otherKey = tenants.apiKey(other, RequiresScope.EXECUTIONS_READ);
        seed = new TraceSeed(jdbc, tenant, Fixtures.pdfExampleJson());
        seed.leadEnrichmentRun();
        seed.strayLlmCallOf(other);
    }

    @Test
    void traceHasNodeTimingsRouterReasonTokensCostAndRetries() throws Exception {
        ResultActions trace = trace(seed.executionId(), key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.executionId").value(seed.executionId().toString()))
                .andExpect(jsonPath("$.data.workflowId").value("lead_enrichment"))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.durationMs").value(9000))
                .andExpect(jsonPath("$.data.nodes.length()").value(4))
                .andExpect(jsonPath("$.data.nodes[*].nodeId")
                        .value(org.hamcrest.Matchers.contains("fetch_leads", "classify_leads", "update_crm",
                                "send_message")))
                // retried node: two attempts, timings span both, last attempt decides status
                .andExpect(jsonPath("$.data.nodes[0].type").value("http"))
                .andExpect(jsonPath("$.data.nodes[0].attempts").value(2))
                .andExpect(jsonPath("$.data.nodes[0].retries").value(1))
                .andExpect(jsonPath("$.data.nodes[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.nodes[0].durationMs").value(3400))
                .andExpect(jsonPath("$.data.nodes[0].attemptHistory[0].errorCode").value("UPSTREAM_TIMEOUT"))
                .andExpect(jsonPath("$.data.nodes[0].attemptHistory[0].durationMs").value(2000))
                .andExpect(jsonPath("$.data.nodes[0].attemptHistory[1].durationMs").value(400))
                // router: primary failed, fallback served; reason, tokens, cost and latency per call
                .andExpect(jsonPath("$.data.nodes[1].type").value("llm"))
                .andExpect(jsonPath("$.data.nodes[1].durationMs").value(2000))
                .andExpect(jsonPath("$.data.nodes[1].llmCalls.length()").value(2))
                .andExpect(jsonPath("$.data.nodes[1].llmCalls[0].provider").value("vllm"))
                .andExpect(jsonPath("$.data.nodes[1].llmCalls[0].reason").value("best_score"))
                .andExpect(jsonPath("$.data.nodes[1].llmCalls[0].outcome").value("FAILED"))
                .andExpect(jsonPath("$.data.nodes[1].llmCalls[1].provider").value("llm-b"))
                .andExpect(jsonPath("$.data.nodes[1].llmCalls[1].model").value("model-b"))
                .andExpect(jsonPath("$.data.nodes[1].llmCalls[1].reason").value("fallback_after_error"))
                .andExpect(jsonPath("$.data.nodes[1].llmCalls[1].promptTokens").value(420))
                .andExpect(jsonPath("$.data.nodes[1].llmCalls[1].completionTokens").value(80))
                .andExpect(jsonPath("$.data.nodes[1].llmCalls[1].costUsd").value(0.002))
                .andExpect(jsonPath("$.data.nodes[1].llmCalls[1].latencyMs").value(1500))
                // tools and the ledger
                .andExpect(jsonPath("$.data.nodes[2].type").value("mcp"))
                .andExpect(jsonPath("$.data.nodes[2].toolCalls[0].tool").value("crm.upsert"))
                .andExpect(jsonPath("$.data.nodes[2].toolCalls[0].latencyMs").value(120))
                .andExpect(jsonPath("$.data.nodes[3].toolCalls[0].outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.nodes[3].sideEffects[0].state").value("COMMITTED"))
                .andExpect(jsonPath("$.data.nodes[3].sideEffects[0].idempotencyMode").value("NONE"))
                // execution totals (the other tenant's stray llm_call row is not counted)
                .andExpect(jsonPath("$.data.totals.nodes").value(4))
                .andExpect(jsonPath("$.data.totals.attempts").value(5))
                .andExpect(jsonPath("$.data.totals.retries").value(1))
                .andExpect(jsonPath("$.data.totals.llmCalls").value(2))
                .andExpect(jsonPath("$.data.totals.llmFallbacks").value(1))
                .andExpect(jsonPath("$.data.totals.promptTokens").value(420))
                .andExpect(jsonPath("$.data.totals.completionTokens").value(80))
                .andExpect(jsonPath("$.data.totals.totalTokens").value(500))
                .andExpect(jsonPath("$.data.totals.costUsd").value(0.002))
                .andExpect(jsonPath("$.data.totals.toolCalls").value(2))
                .andExpect(jsonPath("$.data.totals.budget.limitUsd").value(5))
                .andExpect(jsonPath("$.data.totals.budget.confirmedUsd").value(0.002))
                .andExpect(jsonPath("$.data.totals.budget.reservedUsd").value(0))
                .andExpect(jsonPath("$.data.totals.budget.cancelledReservations").value(1));
        saveSample(trace.andReturn().getResponse().getContentAsString());
    }

    @Test
    void anotherTenantsExecutionIsNotFound() throws Exception {
        trace(seed.executionId(), otherKey)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void anUnknownExecutionIsNotFound() throws Exception {
        trace(UUID.randomUUID(), key).andExpect(status().isNotFound());
    }

    @Test
    void readScopeIsRequired() throws Exception {
        String writeOnly = new TestTenants(jdbc).apiKey(seed.tenant(), RequiresScope.EXECUTIONS_WRITE);
        trace(seed.executionId(), writeOnly).andExpect(status().isForbidden());
    }

    private ResultActions trace(UUID executionId, String apiKey) throws Exception {
        return mvc.perform(get("/v1/executions/" + executionId + "/trace").header("Authorization", "Bearer " + apiKey));
    }

    private void saveSample(String body) throws Exception {
        Files.createDirectories(SAMPLE.getParent());
        Files.writeString(SAMPLE, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(body)));
    }
}
