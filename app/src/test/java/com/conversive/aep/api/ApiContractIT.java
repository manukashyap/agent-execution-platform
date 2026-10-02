package com.conversive.aep.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.conversive.aep.api.auth.RequiresScope;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.engine.workflow.DagInterpreterWorkflow;
import com.conversive.aep.support.Fixtures;
import com.conversive.aep.support.InProcessTemporal;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
@Import(InProcessTemporal.class)
class ApiContractIT extends PostgresIntegrationTest {

    private static final String EXECUTE_BODY = "{\"mode\":\"LIVE\",\"input\":{\"segment\":\"smb\"}}";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    WorkflowClient temporal;

    private TestTenants tenants;
    private TenantId tenant;
    private String key;
    private String otherKey;

    @BeforeEach
    void tenants() {
        tenants = new TestTenants(jdbc);
        tenant = tenants.create("t_api");
        key = tenants.apiKey(tenant, "*");
        otherKey = tenants.apiKey(tenants.create("t_api_other"), "*");
    }

    @Test
    void pdfExamplePostedUnchangedIsCreatedThenExecutes() throws Exception {
        call(post("/v1/workflows").content(Fixtures.pdfExampleJson()), key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.workflowId").value("lead_enrichment"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.meta.warnings").isEmpty());

        String id = executionId(call(post("/v1/workflows/lead_enrichment/executions").content(EXECUTE_BODY), key)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED")));

        WorkflowStub run = temporal.newUntypedWorkflowStub(DagInterpreterWorkflow.workflowId(tenant.value(), id));
        assertThat(run.getResult(JsonNode.class).path("status").asText()).isEqualTo("SUCCEEDED");
        call(get("/v1/executions/" + id), key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executionId").value(id))
                .andExpect(jsonPath("$.data.input.segment").value("smb"))
                .andExpect(jsonPath("$.data.deadlineAt").exists());
        call(get("/v1/executions/" + id + "/nodes"), key).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        call(get("/v1/workflows/lead_enrichment/versions/1"), key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.spec.nodes[0].id").value("fetch_leads"));
    }

    @Test
    void republishingTheSameSpecIsIdempotentAndADifferentSpecIsAConflict() throws Exception {
        call(post("/v1/workflows").content(Fixtures.pdfExampleJson()), key).andExpect(status().isCreated());
        call(post("/v1/workflows").content(Fixtures.pdfExampleJson()), key).andExpect(status().isOk());

        String changed = Fixtures.pdfExampleJson().replace("fetch_leads", "fetch_leads_v2");
        call(post("/v1/workflows").content(changed), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_EXISTS"));
    }

    @Test
    void invalidDefinitionReturnsEveryValidatorError() throws Exception {
        String body = "{\"workflow_id\":\"bad\",\"version\":1,\"nodes\":["
                + "{\"id\":\"a\",\"type\":\"teleport\",\"config\":{},\"depends_on\":[\"b\"]},"
                + "{\"id\":\"b\",\"type\":\"http\",\"config\":{\"url\":\"http://localhost:8090/x\"},\"depends_on\":[\"a\"]}]}";
        call(post("/v1/workflows").content(body), key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[?(@.code == 'UNKNOWN_NODE_TYPE')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.code == 'CYCLE')]").exists())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void sameIdempotencyKeyTwiceReturnsTheSameExecutionAndOneRow() throws Exception {
        publish();
        String idem = "idem-" + UUID.randomUUID();

        String first = executionId(execute(idem).andExpect(status().isAccepted()));
        String second = executionId(execute(idem).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.meta.replayed").value(true)));

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_execution WHERE tenant_id = :t AND idempotency_key = :k")
                .param("t", tenant.value()).param("k", idem).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void anotherTenantsExecutionIsNotFound() throws Exception {
        publish();
        String id = executionId(execute("idem-" + UUID.randomUUID()));

        call(get("/v1/executions/" + id), otherKey)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        call(get("/v1/executions/" + id + "/nodes"), otherKey).andExpect(status().isNotFound());
        call(delete("/v1/executions/" + id), otherKey).andExpect(status().isNotFound());
        call(get("/v1/workflows/lead_enrichment/versions/1"), otherKey).andExpect(status().isNotFound());
        call(post("/v1/workflows/lead_enrichment/executions").content(EXECUTE_BODY), otherKey)
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelOfAFinishedStubRunCancelsTheRowThenConflicts() throws Exception {
        publish();
        String id = executionId(execute("idem-" + UUID.randomUUID()));
        temporal.newUntypedWorkflowStub(DagInterpreterWorkflow.workflowId(tenant.value(), id)).getResult(JsonNode.class);

        call(delete("/v1/executions/" + id), key)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        call(delete("/v1/executions/" + id), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void missingInvalidOrRevokedKeyIsUnauthorized() throws Exception {
        mvc.perform(get("/v1/executions/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        call(get("/v1/executions/" + UUID.randomUUID()), "not-a-key").andExpect(status().isUnauthorized());

        String revoked = tenants.apiKey(tenant, "*");
        tenants.revoke(revoked);
        call(get("/v1/executions/" + UUID.randomUUID()), revoked).andExpect(status().isUnauthorized());

        mvc.perform(get("/v1/executions/" + UUID.randomUUID()).header("X-API-Key", key))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingScopeIsForbidden() throws Exception {
        String readOnly = tenants.apiKey(tenant, RequiresScope.EXECUTIONS_READ, RequiresScope.WORKFLOWS_READ);

        call(post("/v1/workflows").content(Fixtures.pdfExampleJson()), readOnly)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        call(post("/v1/workflows/lead_enrichment/executions").content(EXECUTE_BODY), readOnly)
                .andExpect(status().isForbidden());
        call(get("/v1/executions/" + UUID.randomUUID()), readOnly).andExpect(status().isNotFound());
    }

    @Test
    void toolCatalogIsServedBehindApiKeyAuthAndScopes() throws Exception {
        call(get("/v1/tools"), key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.count").value(0));
        mvc.perform(get("/v1/tools")).andExpect(status().isUnauthorized());
        String executionsOnly = tenants.apiKey(tenant, RequiresScope.EXECUTIONS_READ);
        call(get("/v1/tools"), executionsOnly).andExpect(status().isForbidden());
    }

    @Test
    void previewAndTraceAreNotImplementedYet() throws Exception {
        call(get("/v1/executions/" + UUID.randomUUID() + "/preview"), key)
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"));
        call(get("/v1/executions/" + UUID.randomUUID() + "/trace"), key).andExpect(status().isNotImplemented());
    }

    @Test
    void malformedRequestsAreBadRequests() throws Exception {
        call(get("/v1/executions/not-a-uuid"), key).andExpect(status().isBadRequest());
        call(post("/v1/workflows").content("{not json"), key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        publish();
        call(post("/v1/workflows/lead_enrichment/executions").content("{\"mode\":\"WARP\"}"), key)
                .andExpect(status().isBadRequest());
    }

    private void publish() throws Exception {
        call(post("/v1/workflows").content(Fixtures.pdfExampleJson()), key).andExpect(status().is2xxSuccessful());
    }

    private ResultActions execute(String idempotencyKey) throws Exception {
        return call(post("/v1/workflows/lead_enrichment/executions").content(EXECUTE_BODY)
                .header("Idempotency-Key", idempotencyKey), key);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String apiKey) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + apiKey));
    }

    private String executionId(ResultActions result) throws Exception {
        result.andExpect(header().doesNotExist("Retry-After"));
        return mapper.readTree(result.andReturn().getResponse().getContentAsString())
                .path("data").path("executionId").asText();
    }
}
