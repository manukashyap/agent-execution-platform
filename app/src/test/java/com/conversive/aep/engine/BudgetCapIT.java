package com.conversive.aep.engine;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.execution.ExecutionStatus;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.service.ExecutionService;
import com.conversive.aep.observability.AepMetrics;
import com.conversive.aep.support.InProcessTemporal;
import com.conversive.aep.support.TestTenants;
import com.conversive.aep.support.WireMockToolsIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.client.WorkflowClient;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@code limits.max_cost_usd} through the real interpreter, LLM executor, router and budget service. */
@Import(InProcessTemporal.class)
class BudgetCapIT extends WireMockToolsIntegrationTest {

    private static final BigDecimal CAP = new BigDecimal("0.002");

    /** 100 tokens on model-b (0.004 USD / 1k) = 0.0004 per call: six items cost 0.0024, past the cap. */
    private static final String REPLY = """
            {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":90,"completion_tokens":10,"total_tokens":100}}
            """;

    @DynamicPropertySource
    static void realLlm(DynamicPropertyRegistry registry) {
        registry.add("test.real-node-types", () -> "llm");
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
    MeterRegistry meters;

    private EngineHarness h;

    @BeforeEach
    void setUp() {
        WIRE_MOCK.stubFor(post(urlPathMatching("/llm/.*")).willReturn(okJson(REPLY)));
        h = new EngineHarness(new TestTenants(jdbc).create("t_budget"), definitions, executions, repository,
                client, jdbc, mapper);
    }

    @Test
    void aForEachOverLlmCallsStopsNonRetryablyAtTheExecutionCostCap() {
        h.publish("""
                {"workflow_id":"capped","version":1,"limits":{"max_cost_usd":0.002},"nodes":[
                  {"id":"each","type":"llm","for_each":{"items":"$.input.items","max_concurrency":1},
                   "config":{"prompt":"say {{item}}","model":"model-b"}}]}
                """);
        ExecutionId id = h.start("capped", null, "{\"items\":[1,2,3,4,5,6]}");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(h.row(id).errorCode()).isEqualTo(ErrorCodes.BUDGET_EXCEEDED);
        Map<String, Object> budget = jdbc.sql("""
                        SELECT limit_usd, reserved_usd, spent_usd FROM execution_budget
                        WHERE tenant_id = :tenantId AND execution_id = :executionId""")
                .param("tenantId", h.tenant().value()).param("executionId", id.value())
                .query().singleRow();
        BigDecimal spent = (BigDecimal) budget.get("spent_usd");
        BigDecimal reserved = (BigDecimal) budget.get("reserved_usd");
        assertThat((BigDecimal) budget.get("limit_usd")).isEqualByComparingTo(CAP);
        assertThat(spent).isPositive();
        assertThat(spent.add(reserved)).isLessThanOrEqualTo(CAP);
        assertThat(reservations(id, "RESERVED")).isZero();
        assertThat(reservations(id, "CONFIRMED")).isEqualTo(llmCalls()).isBetween(1, 5);
        assertThat(meters.find(AepMetrics.WORKFLOW_EXECUTIONS).tag(AepMetrics.TAG_WORKFLOW_ID, "capped")
                .tag(AepMetrics.TAG_STATUS, "FAILED").counter()).isNotNull();
        assertThat(meters.find(AepMetrics.NODE_EXECUTION_LATENCY).tag(AepMetrics.TAG_NODE_TYPE, "llm")
                .tag(AepMetrics.TAG_STATUS, "FAILED").timer()).isNotNull();
    }

    private int reservations(ExecutionId id, String status) {
        return jdbc.sql("""
                        SELECT count(*) FROM budget_reservation
                        WHERE tenant_id = :tenantId AND execution_id = :executionId AND status = :status""")
                .param("tenantId", h.tenant().value()).param("executionId", id.value()).param("status", status)
                .query(Integer.class).single();
    }

    private int llmCalls() {
        return (int) WIRE_MOCK.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().startsWith("/llm/")).count();
    }
}
