package com.conversive.aep.sideeffect;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.engine.EngineHarness;
import com.conversive.aep.execution.ExecutionStatus;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.persistence.NodeRunRecord;
import com.conversive.aep.execution.service.ExecutionService;
import com.conversive.aep.support.InProcessTemporal;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import com.conversive.aep.support.WireMockToolsIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.temporal.client.WorkflowClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 06 §4.9 tests 1, 2, 3 and 5 end to end: the real interpreter on in-process Temporal, real http and mcp nodes, the
 * real ledger guard and reconciler, and an idempotent provider stub. Test 4 (refund always 500) is
 * {@code SagaIT.aRefundThatAlwaysFailsEndsCompensationFailedAfterBoundedRetries}.
 *
 * <p>The PDF §9 timing (provider 15 s, timeout 10 s) is scaled down to provider 3 s, {@code timeout_s} 2: the client
 * gives up at 1 s and the lease ends 7 s after the attempt began. The run uses real time: the lease is computed from
 * the app clock, which Temporal's time-skipping cannot advance.
 */
@Import({InProcessTemporal.class, SideEffectEngineIT.CrashSwitchConfig.class})
class SideEffectEngineIT extends PostgresIntegrationTest {

    private static final IdempotentProviderStub PROVIDER = new IdempotentProviderStub();
    private static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort().extensions(PROVIDER));
    private static final AtomicBoolean CRASH_AFTER_CALL = new AtomicBoolean();
    private static final Duration PROVIDER_LATENCY = Duration.ofSeconds(3);
    private static final Duration LEASE = Duration.ofSeconds(7);

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

    /** Wraps the real guard: when armed, the next guarded call "crashes the worker" after the provider answered. */
    @TestConfiguration(proxyBeanMethods = false)
    static class CrashSwitchConfig {

        @Bean
        static BeanPostProcessor crashAfterCallGuard() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof LedgerSideEffectGuard real)) {
                        return bean;
                    }
                    return (SideEffectGuard) (spec, call) -> real.run(spec, key -> {
                        JsonNode answer = call.invoke(key);
                        if (CRASH_AFTER_CALL.getAndSet(false)) {
                            throw new WorkerCrash();
                        }
                        return answer;
                    });
                }
            };
        }
    }

    /**
     * Stands in for a worker dying between the provider call and the ledger commit: an {@link Error}, so no
     * application handler records the attempt and the ledger row stays PENDING with its lease.
     */
    static final class WorkerCrash extends Error {
        WorkerCrash() {
            super("simulated worker crash between call and commit");
        }
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
        PROVIDER.reset(Duration.ZERO);
        CRASH_AFTER_CALL.set(false);
        WIRE_MOCK.stubFor(post(urlEqualTo("/charge")).willReturn(okJson("{}")
                .withTransformers(IdempotentProviderStub.NAME)));
        WIRE_MOCK.stubFor(post(urlEqualTo("/refund")).willReturn(okJson("{\"refund_id\":\"re_1\"}")));
        WIRE_MOCK.stubFor(post(urlEqualTo("/recall")).willReturn(okJson("{\"recalled\":true}")));
        WIRE_MOCK.stubFor(post(urlEqualTo("/mcp")).willReturn(okJson(WireMockToolsIntegrationTest.toolResult(
                "{\"message_id\":\"msg_1\",\"status\":\"sent\"}", false)).withFixedDelay(
                (int) PROVIDER_LATENCY.toMillis())));
        TenantId tenant = new TestTenants(jdbc).create("t_fx");
        jdbc.sql("INSERT INTO tenant_tool_grant (tenant_id, tool_name, scopes) VALUES (:t, 'messaging.send', "
                        + "'{messaging:send}')")
                .param("t", tenant.value()).update();
        h = new EngineHarness(tenant, definitions, executions, repository, client, jdbc, mapper);
    }

    @AfterEach
    void disarm() {
        CRASH_AFTER_CALL.set(false);
    }

    /** Test 1: the charge happened, the attempt died before the commit; the retry reconciles, no second charge. */
    @Test
    void aCrashBetweenCallAndCommitNeverChargesTwice() {
        CRASH_AFTER_CALL.set(true);
        h.publish(chargeOnly("crash"));
        ExecutionId id = h.start("crash");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(PROVIDER.performedEffects()).isEqualTo(1);
        assertThat(h.output(id, "charge").path("body").path("charge_id").asText()).isEqualTo("ch_1");
        assertThat(ledgerState(id, "charge")).isEqualTo("COMMITTED");
        List<NodeRunRecord> runs = attempts(id, "charge");
        assertThat(runs).extracting(NodeRunRecord::status).endsWith("SUCCEEDED");
        assertThat(runs.get(runs.size() - 1).startedAt()).isAfterOrEqualTo(
                runs.get(0).startedAt().plus(LEASE).minusMillis(500));
    }

    /** Test 2 (PDF §9, NATIVE_KEY): attempt 2 waits out the lease, the re-call returns the stored charge. */
    @Test
    void aSlowNativeKeyProviderIsChargedOnceAndTheRetryWaitsForTheLease() {
        PROVIDER.reset(PROVIDER_LATENCY);
        h.publish(chargeOnly("slow_native"));
        ExecutionId id = h.start("slow_native");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(h.nodeStatuses(id)).containsEntry("charge", "SUCCEEDED");
        assertThat(PROVIDER.performedEffects()).isEqualTo(1);
        assertThat(requests("/charge")).hasSizeGreaterThanOrEqualTo(2);
        List<NodeRunRecord> runs = attempts(id, "charge");
        assertThat(runs).extracting(NodeRunRecord::status).containsExactly("FAILED", "FAILED", "SUCCEEDED");
        assertThat(runs.get(0).errorCode()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(runs.get(1).errorCode()).isEqualTo("EFFECT_IN_PROGRESS");
        Instant leaseEnd = runs.get(0).startedAt().plus(LEASE);
        assertThat(runs.get(2).startedAt()).isAfterOrEqualTo(leaseEnd.minusMillis(500));
    }

    /** Test 3 (PDF §9, NONE): same timing on a non-idempotent tool; no second call, the node needs attention. */
    @Test
    void aSlowNoneModeToolIsNeverCalledTwiceAndNeedsAttention() {
        h.publish("""
                {"workflow_id":"slow_none","version":1,"nodes":[
                  {"id":"notify","type":"mcp","timeout_s":2,
                   "retry":{"max_attempts":3,"initial_interval_ms":50},
                   "config":{"tool":"messaging.send","args":{"to":"lead_1","body":"hi"}}},
                  {"id":"after","type":"http","depends_on":["notify"],
                   "config":{"method":"POST","url":"%s/recall"}}]}
                """.formatted(WIRE_MOCK.baseUrl()));
        ExecutionId id = h.start("slow_none");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.NEEDS_ATTENTION);
        assertThat(h.row(id).errorCode()).isEqualTo("NEEDS_ATTENTION");
        assertThat(h.nodeStatuses(id)).containsEntry("notify", "NEEDS_ATTENTION")
                .doesNotContainEntry("after", "SUCCEEDED");
        assertThat(requests("/mcp")).hasSize(1);
        assertThat(requests("/recall")).isEmpty();
        assertThat(ledgerState(id, "notify")).isEqualTo("UNKNOWN");
    }

    /** Test 5: the UNKNOWN pivot send is not compensated blindly and stops the walk: the charge before it stays. */
    @Test
    void anUnknownPivotStopsTheSagaWalkAndLeavesEarlierEffectsAlone() {
        String base = WIRE_MOCK.baseUrl();
        h.publish("""
                {"workflow_id":"unknown_fx","version":1,"nodes":[
                  {"id":"charge","type":"http","side_effecting":true,
                   "config":{"method":"POST","url":"%s/charge","body":{"amount":42}},
                   "compensate":{"type":"http","config":{"method":"POST","url":"%s/refund",
                                 "body":{"charge_id":"{{forward.body.charge_id}}"}}}},
                  {"id":"notify","type":"mcp","depends_on":["charge"],"timeout_s":2,
                   "retry":{"max_attempts":3,"initial_interval_ms":50},
                   "config":{"tool":"messaging.send","args":{"to":"lead_1","body":"hi"}},
                   "compensate":{"type":"http","config":{"method":"POST","url":"%s/recall"}}}]}
                """.formatted(base, base, base));
        ExecutionId id = h.start("unknown_fx");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.NEEDS_ATTENTION);
        assertThat(h.row(id).errorCode()).isEqualTo("NEEDS_ATTENTION");
        assertThat(h.row(id).errorMessage()).startsWith("compensation of notify needs attention");
        assertThat(h.nodeStatuses(id)).containsEntry("charge", "SUCCEEDED").containsEntry("notify", "NEEDS_ATTENTION");
        assertThat(ledgerState(id, "notify")).isEqualTo("UNKNOWN");
        assertThat(requests("/mcp")).hasSize(1);
        assertThat(requests("/recall")).isEmpty();
        assertThat(PROVIDER.performedEffects()).isEqualTo(1);
        assertThat(requests("/refund")).isEmpty();
    }

    private String chargeOnly(String workflowId) {
        return """
                {"workflow_id":"%s","version":1,"nodes":[
                  {"id":"charge","type":"http","side_effecting":true,"timeout_s":2,
                   "retry":{"max_attempts":3,"initial_interval_ms":50},
                   "config":{"method":"POST","url":"%s/charge","body":{"amount":42}},
                   "compensate":{"type":"http","config":{"method":"POST","url":"%s/refund"}}}]}
                """.formatted(workflowId, WIRE_MOCK.baseUrl(), WIRE_MOCK.baseUrl());
    }

    private List<NodeRunRecord> attempts(ExecutionId id, String nodeId) {
        return h.runs(id).stream()
                .filter(r -> "FORWARD".equals(r.phase()) && nodeId.equals(r.nodeId()))
                .sorted(Comparator.comparingInt(NodeRunRecord::attempt))
                .toList();
    }

    private String ledgerState(ExecutionId id, String nodeId) {
        return jdbc.sql("""
                        SELECT state FROM side_effect_ledger
                         WHERE tenant_id = :t AND execution_id = :e AND node_id = :n AND phase = 'FORWARD'
                        """)
                .param("t", h.tenant().value()).param("e", id.value()).param("n", nodeId)
                .query(String.class).single();
    }

    private static List<LoggedRequest> requests(String path) {
        return WIRE_MOCK.findAll(anyRequestedFor(urlEqualTo(path)));
    }
}
