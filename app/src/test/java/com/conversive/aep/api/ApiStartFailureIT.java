package com.conversive.aep.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.support.Fixtures;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Temporal is unreachable: the start fails after the inline retries → 503 + Retry-After, row START_FAILED. */
@AutoConfigureMockMvc
@Import(ApiStartFailureIT.UnreachableTemporal.class)
@TestPropertySource(properties = {"aep.engine.launcher.start-backoff=0ms", "aep.engine.launcher.retry-after=7s"})
class ApiStartFailureIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;

    @TestConfiguration(proxyBeanMethods = false)
    static class UnreachableTemporal {

        /** Primary, so the app's own WorkflowClient (and so the launcher) talks to a closed port. */
        @Bean(destroyMethod = "shutdownNow")
        @Primary
        WorkflowServiceStubs unreachableStubs() {
            return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                    .setTarget("127.0.0.1:1")
                    .setRpcTimeout(Duration.ofMillis(500))
                    .setRpcRetryOptions(RpcRetryOptions.newBuilder()
                            .setMaximumAttempts(1)
                            .setExpiration(Duration.ofSeconds(1))
                            .validateBuildWithDefaults())
                    .build());
        }
    }

    @Test
    void startFailureReturns503WithRetryAfterAndMarksTheRowStartFailed() throws Exception {
        TestTenants tenants = new TestTenants(jdbc);
        TenantId tenant = tenants.create("t_start");
        String key = tenants.apiKey(tenant, "*");
        mvc.perform(post("/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + key).content(Fixtures.pdfExampleJson()))
                .andExpect(status().isCreated());

        mvc.perform(post("/v1/workflows/lead_enrichment/executions").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + key).header("Idempotency-Key", "start-fail-1")
                        .content("{\"input\":{}}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "7"))
                .andExpect(jsonPath("$.error.code").value("START_FAILED"));

        assertThat(jdbc.sql("SELECT status FROM workflow_execution WHERE tenant_id = :t AND idempotency_key = :k")
                .param("t", tenant.value()).param("k", "start-fail-1").query(String.class).single())
                .isEqualTo("START_FAILED");
    }
}
