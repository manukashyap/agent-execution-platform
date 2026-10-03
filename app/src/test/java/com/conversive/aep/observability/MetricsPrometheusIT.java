package com.conversive.aep.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.observability.persistence.QueuedExecutionCounter;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** T8.1: the 9 PDF metrics (and the 06 §4.13 extras) on {@code /actuator/prometheus}, with their tags, never a tenant id. */
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
class MetricsPrometheusIT extends PostgresIntegrationTest {

    private static final Pattern SAMPLE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{([^}]*)})? .*$");

    /** Prometheus series name → the label keys it must carry (exactly; {@code le} added for histogram buckets). */
    private static final Map<String, Set<String>> REQUIRED = Map.ofEntries(
            Map.entry("workflow_execution_latency_seconds_bucket", Set.of("tenant_tier", "workflow_id", "status", "le")),
            Map.entry("workflow_executions_total", Set.of("tenant_tier", "workflow_id", "status")),
            Map.entry("node_execution_latency_seconds_bucket",
                    Set.of("tenant_tier", "workflow_id", "node_type", "status", "le")),
            Map.entry("llm_latency_seconds_bucket", Set.of("tenant_tier", "provider", "model", "outcome", "le")),
            Map.entry("llm_tokens_total", Set.of("tenant_tier", "provider", "model", "direction")),
            Map.entry("llm_cost_usd_total", Set.of("tenant_tier", "provider", "model")),
            Map.entry("node_retries_total", Set.of("tenant_tier", "workflow_id", "node_type")),
            Map.entry("queue_depth", Set.of("source")),
            Map.entry("provider_errors_total", Set.of("provider", "error_class")),
            Map.entry("compensations_total", Set.of("outcome")),
            Map.entry("router_decisions_total", Set.of("provider", "reason")),
            Map.entry("side_effect_unknown_total", Set.of()),
            Map.entry("schedule_to_start_seconds_bucket", Set.of("tenant_tier", "le")),
            Map.entry("admission_rejections_total", Set.of("tenant_tier", "reason")),
            Map.entry("budget_rejections_total", Set.of("tenant_tier", "reason")),
            Map.entry("tool_call_latency_seconds_bucket", Set.of("tenant_tier", "provider", "outcome", "le")));

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AepMetrics metrics;
    @Autowired
    QueueDepthMonitor queueDepthMonitor;
    @Autowired
    QueuedExecutionCounter queuedExecutions;

    private TenantId tenant;

    @BeforeEach
    void enterpriseTenant() {
        tenant = new TestTenants(jdbc).create("t_metrics");
        jdbc.sql("UPDATE tenant SET tier = 'ENTERPRISE' WHERE id = :id").param("id", tenant.value()).update();
    }

    @Test
    void everyRequiredMetricIsExposedWithItsTagsAndNoTenantId() throws Exception {
        recordOneOfEach();

        String body = scrape();

        for (Map.Entry<String, Set<String>> required : REQUIRED.entrySet()) {
            List<Set<String>> labelSets = series(body, required.getKey());
            assertThat(labelSets).as(required.getKey() + " is exposed").isNotEmpty();
            assertThat(labelSets).as(required.getKey() + " labels").allSatisfy(labels ->
                    assertThat(labels).containsExactlyInAnyOrderElementsOf(required.getValue()));
        }
        assertThat(body).contains("tenant_tier=\"ENTERPRISE\"");
        assertThat(body).doesNotContain("tenant_id").doesNotContain(tenant.value());
    }

    @Test
    void queueDepthHasOneSeriesPerSourceAndNoTenantLabel() throws Exception {
        long queued = queuedExecutions.countQueued();
        queueDepthMonitor.refresh();

        String body = scrape();

        assertThat(body).containsPattern("(?m)^queue_depth\\{source=\"admission_queued\"} " + queued + "(\\.0)?$");
        assertThat(body).contains("queue_depth{source=\"temporal_workflow\"}", "queue_depth{source=\"temporal_activity\"}");
    }

    /** Engine-level call sites are not wired yet (P2a), so they are driven through their public methods. */
    private void recordOneOfEach() {
        metrics.executionCompleted(tenant, "lead_enrichment", "SUCCEEDED", Duration.ofSeconds(3));
        metrics.nodeCompleted(tenant, "lead_enrichment", "llm", "SUCCEEDED", Duration.ofMillis(800));
        metrics.nodeRetried(tenant, "lead_enrichment", "http");
        metrics.llmCall(tenant, "vllm", "vllm-default", 120, 40, new BigDecimal("0.00032"), Duration.ofMillis(90), null);
        metrics.llmCall(tenant, "llm-a", "model-a", 0, 0, BigDecimal.ZERO, Duration.ofSeconds(2), "UPSTREAM_TIMEOUT");
        metrics.routerDecision("vllm", "best_score");
        metrics.compensation("COMPENSATED");
        metrics.sideEffectUnknown();
        metrics.scheduleToStart(tenant, Duration.ofMillis(15));
        metrics.admissionRejected(tenant, "RATE_LIMITED");
        metrics.budgetRejected(tenant, "execution_cap");
        metrics.toolCall(tenant, "crm.get", "SUCCEEDED", null, Duration.ofMillis(30));
        metrics.queueDepth(AepMetrics.SOURCE_TEMPORAL_WORKFLOW, 3);
    }

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static List<Set<String>> series(String body, String name) {
        return Arrays.stream(body.split("\n"))
                .map(SAMPLE::matcher)
                .filter(m -> m.matches() && m.group(1).equals(name))
                .map(MetricsPrometheusIT::labelKeys)
                .toList();
    }

    private static Set<String> labelKeys(Matcher sample) {
        Set<String> keys = new TreeSet<>();
        String labels = sample.group(3);
        if (labels == null || labels.isBlank()) {
            return keys;
        }
        Matcher key = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"").matcher(labels);
        while (key.find()) {
            keys.add(key.group(1));
        }
        return keys;
    }
}
