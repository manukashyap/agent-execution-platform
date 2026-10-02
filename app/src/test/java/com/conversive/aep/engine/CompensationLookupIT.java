package com.conversive.aep.engine;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.execution.ExecutionStatus;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.service.ExecutionService;
import com.conversive.aep.support.InProcessTemporal;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import com.conversive.aep.support.WireMockToolsIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.temporal.client.WorkflowClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A LOOKUP forward (crm.upsert) interrupted mid-call leaves a PENDING row; the saga must ask the provider
 * (crm.get) whether the effect landed and delete the contact, not assume it did not.
 */
@Import(InProcessTemporal.class)
class CompensationLookupIT extends PostgresIntegrationTest {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void realNodes(DynamicPropertyRegistry registry) {
        registry.add("aep.outbound.allow-hosts", () -> "localhost:" + WIRE_MOCK.port());
        registry.add("aep.tools.mcp-url", () -> WIRE_MOCK.baseUrl() + "/mcp");
        registry.add("aep.tools.dev-credential", () -> WireMockToolsIntegrationTest.TEST_CREDENTIAL);
        registry.add("test.real-node-types", () -> "http,mcp");
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
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

    private EngineHarness h;

    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(post(urlEqualTo("/mcp")).withRequestBody(containing("\"crm.upsert\""))
                .willReturn(okJson(WireMockToolsIntegrationTest.toolResult("{\"external_ref\":\"lead_9\"}", false))
                        .withFixedDelay(3000)));
        WIRE_MOCK.stubFor(post(urlEqualTo("/mcp")).withRequestBody(containing("\"crm.get\""))
                .willReturn(okJson(WireMockToolsIntegrationTest.toolResult(
                        "{\"contacts\":[{\"external_ref\":\"lead_9\",\"id\":\"c_1\"}]}", false))));
        WIRE_MOCK.stubFor(post(urlEqualTo("/mcp")).withRequestBody(containing("\"crm.delete\""))
                .willReturn(okJson(WireMockToolsIntegrationTest.toolResult(
                        "{\"external_ref\":\"lead_9\",\"deleted\":true}", false))));
        TenantId tenant = new TestTenants(jdbc).create("t_lk");
        grant(tenant, "crm.upsert", "{crm:write}");
        grant(tenant, "crm.delete", "{crm:write}");
        grant(tenant, "crm.get", "{crm:read}");
        h = new EngineHarness(tenant, definitions, executions, repository, client, jdbc, mapper);
    }

    private void grant(TenantId tenant, String tool, String scopes) {
        jdbc.sql("INSERT INTO tenant_tool_grant (tenant_id, tool_name, scopes) VALUES (:t, :tool, :scopes::text[])")
                .param("t", tenant.value()).param("tool", tool).param("scopes", scopes).update();
    }

    @Test
    void anInterruptedLookupForwardIsLookedUpAndItsContactDeleted() throws Exception {
        h.publish("""
                {"workflow_id":"lk","version":1,"nodes":[
                  {"id":"upsert","type":"mcp","timeout_s":2,"retry":{"max_attempts":1},
                   "config":{"tool":"crm.upsert","args":{"external_ref":"lead_9","name":"Ada","email":"a@example.com"}},
                   "compensate":{"type":"mcp","config":{"tool":"crm.delete","args":{"external_ref":"lead_9"}}}}
                  ]}
                """);
        ExecutionId id = h.start("lk");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.COMPENSATED);
        assertThat(mcpCalls("crm.get")).isGreaterThanOrEqualTo(1);
        assertThat(mcpCalls("crm.delete")).isEqualTo(1);
    }

    private int forwardLedgerRows(ExecutionId id) {
        return jdbc.sql("SELECT count(*) FROM side_effect_ledger WHERE tenant_id = :t AND execution_id = :e")
                .param("t", h.tenant().value()).param("e", id.value()).query(Integer.class).single();
    }

    private static long mcpCalls(String tool) {
        return WIRE_MOCK.findAll(anyRequestedFor(urlEqualTo("/mcp"))).stream()
                .filter(r -> r.getBodyAsString().contains("\"" + tool + "\"")).count();
    }
}
