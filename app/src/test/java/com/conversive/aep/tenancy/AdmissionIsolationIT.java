package com.conversive.aep.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.support.Fixtures;
import com.conversive.aep.support.InProcessTemporal;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** T7.1 through the real filter chain: per-tenant token bucket and soft concurrency cap → 429 + Retry-After. */
@AutoConfigureMockMvc
@Import(InProcessTemporal.class)
class AdmissionIsolationIT extends PostgresIntegrationTest {

    private static final String EXECUTE_BODY = "{\"mode\":\"LIVE\",\"input\":{\"segment\":\"smb\"}}";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;

    private TestTenants tenants;

    @BeforeEach
    void setUp() {
        tenants = new TestTenants(jdbc);
    }

    @Test
    void tenantFloodingAtTenTimesItsLimitIsRejectedWhileAnotherTenantIsFullyAdmitted() throws Exception {
        String a = tenantWithKey("t_flood", "0.01", 2, 50);
        String b = tenantWithKey("t_calm", "10", 20, 50);
        List<MockHttpServletResponse> aResponses = new ArrayList<>();
        List<Integer> bStatuses = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            aResponses.add(execute(a, "a-" + i).andReturn().getResponse());
            if (i % 2 == 0) {
                bStatuses.add(execute(b, "b-" + i).andReturn().getResponse().getStatus());
            }
        }

        assertThat(bStatuses).hasSize(10).allMatch(s -> s == 202);
        assertThat(aResponses).filteredOn(r -> r.getStatus() == 202).hasSize(2);
        List<MockHttpServletResponse> rejected = aResponses.stream().filter(r -> r.getStatus() != 202).toList();
        assertThat(rejected).hasSize(18).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(429);
            assertThat(Long.parseLong(r.getHeader("Retry-After"))).isPositive();
            assertThat(r.getContentAsString()).contains("\"RATE_LIMITED\"");
        });
    }

    @Test
    void idempotentReplayIsNeverRateLimited() throws Exception {
        String key = tenantWithKey("t_replay", "0.01", 1, 50);

        execute(key, "first").andExpect(status().isAccepted());
        execute(key, "second").andExpect(status().isTooManyRequests());

        execute(key, "first")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.meta.replayed").value(true));
    }

    @Test
    void tenantAtItsConcurrencyCapGets429UntilALiveSlotExpires() throws Exception {
        TenantId tenant = tenants.create("t_cap");
        limits(tenant, "100", 100, 2);
        String key = tenants.apiKey(tenant, "*");
        publish(key);
        insertLive(tenant, "1 hour");
        insertLive(tenant, "1 hour");

        execute(key, "over-cap")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.error.code").value("CONCURRENCY_LIMIT"));

        jdbc.sql("UPDATE workflow_execution SET deadline_at = now() - interval '1 second' WHERE tenant_id = :t")
                .param("t", tenant.value()).update();
        execute(key, "after-expiry").andExpect(status().isAccepted());
    }

    private String tenantWithKey(String prefix, String rate, int burst, int maxConcurrent) throws Exception {
        TenantId tenant = tenants.create(prefix);
        limits(tenant, rate, burst, maxConcurrent);
        String key = tenants.apiKey(tenant, "*");
        publish(key);
        return key;
    }

    private void limits(TenantId tenant, String rate, int burst, int maxConcurrent) {
        jdbc.sql("""
                UPDATE tenant_limits SET rate_per_sec = CAST(:rate AS numeric), burst = :burst,
                    max_concurrent = :maxConcurrent WHERE tenant_id = :t""")
                .param("rate", rate).param("burst", burst).param("maxConcurrent", maxConcurrent)
                .param("t", tenant.value()).update();
    }

    private void insertLive(TenantId tenant, String deadlineIn) {
        jdbc.sql("""
                INSERT INTO workflow_execution (id, tenant_id, workflow_id, def_version, status, deadline_at)
                VALUES (:id, :t, 'lead_enrichment', 1, 'RUNNING', now() + CAST(:in AS interval))""")
                .param("id", UUID.randomUUID()).param("t", tenant.value()).param("in", deadlineIn).update();
    }

    private void publish(String key) throws Exception {
        mvc.perform(post("/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + key).content(Fixtures.pdfExampleJson()))
                .andExpect(status().isCreated());
    }

    private ResultActions execute(String key, String idempotencyKey) throws Exception {
        return mvc.perform(post("/v1/workflows/lead_enrichment/executions").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + key).header("Idempotency-Key", idempotencyKey)
                .content(EXECUTE_BODY));
    }
}
