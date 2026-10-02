package com.conversive.aep.tools.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.TenantContext;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.support.WireMockToolsIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code GET /v1/tools} lists only the tenant's granted tools. The tenant is set on the test thread, standing in for
 * the P1 API-key filter (MockMvc runs the handler on the calling thread).
 */
class ToolCatalogControllerIT extends WireMockToolsIntegrationTest {

    @Autowired
    private ToolCatalogController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void listsOnlyTheGrantedToolsWithSchemaAndSemantics() throws Exception {
        TenantContext.set(TenantId.of("t_other"), null);

        mvc.perform(get("/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.meta.count").value(2))
                .andExpect(jsonPath("$.data[*].name", Matchers.contains("crm.get", "leads.fetch")))
                .andExpect(jsonPath("$.data[0].reversibility").value("READ_ONLY"))
                .andExpect(jsonPath("$.data[0].idempotency").value("NONE"))
                .andExpect(jsonPath("$.data[0].inputSchema.required[0]").value("external_ref"))
                .andExpect(jsonPath("$.data[0].description").isNotEmpty());
    }

    @Test
    void devTenantSeesAllSevenTools() throws Exception {
        TenantContext.set(TenantId.of("t_dev"), null);

        mvc.perform(get("/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.count").value(7))
                .andExpect(jsonPath("$.data[?(@.name=='payments.charge')].reversibility").value("COMPENSATABLE"));
    }

    @Test
    void withoutATenantIsUnauthorizedInTheEnvelope() throws Exception {
        mvc.perform(get("/v1/tools"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.error.code").value(ErrorCodes.UNAUTHORIZED));
    }
}
