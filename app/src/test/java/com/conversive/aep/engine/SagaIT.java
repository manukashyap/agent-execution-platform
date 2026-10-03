package com.conversive.aep.engine;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.engine.activity.ExecutionStateActivity.Transition;
import com.conversive.aep.engine.activity.ExecutionStateActivity.TransitionResult;
import com.conversive.aep.engine.activity.ExecutionStateActivityImpl;
import com.conversive.aep.execution.ExecutionStatus;
import com.conversive.aep.execution.persistence.ExecutionRecord;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.persistence.NodeRunRecord;
import com.conversive.aep.execution.service.ExecutionService;
import com.conversive.aep.support.InProcessTemporal;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.temporal.client.WorkflowClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The P2b saga (06 §4.4 / T2b): real http nodes against WireMock, the real ledger guard and reconciler. */
@Import(InProcessTemporal.class)
class SagaIT extends PostgresIntegrationTest {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void realHttp(DynamicPropertyRegistry registry) {
        registry.add("aep.outbound.allow-hosts", () -> "localhost:" + WIRE_MOCK.port());
        registry.add("test.real-node-types", () -> "http");
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
    ExecutionStateActivityImpl stateActivity;
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
        WIRE_MOCK.stubFor(post(urlEqualTo("/charge"))
                .willReturn(okJson("{\"charge_id\":\"ch_1\"}")));
        WIRE_MOCK.stubFor(post(urlEqualTo("/refund"))
                .willReturn(okJson("{\"refunded\":true}")));
        WIRE_MOCK.stubFor(post(urlEqualTo("/send"))
                .willReturn(aResponse().withStatus(500)));
        WIRE_MOCK.stubFor(get(urlEqualTo("/validate"))
                .willReturn(aResponse().withStatus(400)));
        h = new EngineHarness(new TestTenants(jdbc).create("t_saga"), definitions, executions, repository, client,
                jdbc, mapper);
    }

    @Test
    void chargeThenSendFailsRefundsExactlyOnce() {
        h.publish(spec("charge_send", "", "\"depends_on\":[\"charge\"],", "POST", "/send"));
        ExecutionId id = h.start("charge_send");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.COMPENSATED);
        assertThat(requests("/charge")).hasSize(1);
        List<LoggedRequest> refunds = requests("/refund");
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0).getHeader("Idempotency-Key")).matches("[0-9a-f]{64}")
                .isNotEqualTo(requests("/charge").get(0).getHeader("Idempotency-Key"));
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/refund")).withRequestBody(equalToJson(
                "{\"charge_id\":\"ch_1\"}")));
        ExecutionRecord row = h.row(id);
        assertThat(row.errorCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(compensateRuns(id)).extracting(NodeRunRecord::nodeId, NodeRunRecord::status)
                .containsExactly(tuple("charge", "SUCCEEDED"));
    }

    @Test
    void siblingFailsWhileAChargeIsInFlightRefundsExactlyOnce() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/charge"))
                .willReturn(okJson("{\"charge_id\":\"ch_1\"}").withFixedDelay(1500)));
        h.publish(spec("sibling", "", "", "GET", "/validate"));
        ExecutionId id = h.start("sibling");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.COMPENSATED);
        assertThat(requests("/charge")).hasSize(1);
        assertThat(requests("/refund")).hasSize(1);
        assertThat(h.row(id).errorCode()).isEqualTo("UPSTREAM_CLIENT_ERROR");
    }

    @Test
    void aCancelDuringCompensationAndALateProjectionNeverOverwriteTheTerminalState() throws Exception {
        WIRE_MOCK.stubFor(post(urlEqualTo("/refund"))
                .willReturn(okJson("{\"refunded\":true}").withFixedDelay(1500)));
        h.publish(spec("late", "", "\"depends_on\":[\"charge\"],", "POST", "/send"));
        ExecutionId id = h.start("late");
        EngineHarness.waitUntil(() -> h.row(id).status() == ExecutionStatus.COMPENSATING, Duration.ofSeconds(20));
        executions.cancel(h.tenant(), id);

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.COMPENSATED);
        ExecutionRecord done = h.row(id);
        TransitionResult late = stateActivity.transition(new Transition(h.tenant(), id, Set.of(
                ExecutionStatus.QUEUED, ExecutionStatus.RUNNING, ExecutionStatus.COMPENSATING),
                ExecutionStatus.CANCELLED, "CANCELLED", "late projection", null));

        assertThat(late.applied()).isFalse();
        assertThat(late.current()).isEqualTo(ExecutionStatus.COMPENSATED);
        assertThat(h.row(id).status()).isEqualTo(ExecutionStatus.COMPENSATED);
        assertThat(h.row(id).rowVersion()).isEqualTo(done.rowVersion());
        assertThat(requests("/refund")).hasSize(1);
    }

    @Test
    void aRefundThatAlwaysFailsEndsCompensationFailedAfterBoundedRetries() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/refund"))
                .willReturn(aResponse().withStatus(500)));
        h.publish(spec("refund_fails", "\"retry\":{\"max_attempts\":3,\"initial_interval_ms\":50},",
                "\"depends_on\":[\"charge\"],", "POST", "/send"));
        ExecutionId id = h.start("refund_fails");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.COMPENSATION_FAILED);
        assertThat(requests("/refund")).hasSize(3);
        assertThat(h.row(id).errorCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(h.row(id).errorMessage()).startsWith("compensation of charge failed");
    }

    @Test
    void aFailureBeforeAnySideEffectDoesNotCompensate() {
        h.publish(spec("nothing_charged", "", "\"depends_on\":[\"charge\"],", "POST", "/send"));
        WIRE_MOCK.stubFor(post(urlEqualTo("/charge"))
                .willReturn(aResponse().withStatus(422)));
        ExecutionId id = h.start("nothing_charged");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(requests("/refund")).isEmpty();
        assertThat(compensateRuns(id)).isEmpty();
    }

    /**
     * A side-effecting compensatable {@code charge} and a second node {@code other} hitting {@code otherPath}.
     *
     * @param chargeExtra extra JSON members for the charge node
     * @param otherDeps   {@code depends_on} member (with trailing comma) for the second node, or empty
     */
    private String spec(String workflowId, String chargeExtra, String otherDeps, String otherMethod,
                        String otherPath) {
        String base = WIRE_MOCK.baseUrl();
        return """
                {"workflow_id":"%s","version":1,"nodes":[
                  {"id":"charge","type":"http","side_effecting":true,%s
                   "config":{"method":"POST","url":"%s/charge","body":{"amount":42}},
                   "compensate":{"type":"http","config":{"method":"POST","url":"%s/refund",
                                 "body":{"charge_id":"{{forward.body.charge_id}}"}}}},
                  {"id":"other","type":"http",%s"config":{"method":"%s","url":"%s%s"}}]}
                """.formatted(workflowId, chargeExtra, base, base, otherDeps, otherMethod, base, otherPath);
    }

    private static List<LoggedRequest> requests(String path) {
        return WIRE_MOCK.findAll(anyRequestedFor(urlEqualTo(path)));
    }

    private List<NodeRunRecord> compensateRuns(ExecutionId id) {
        return h.runs(id).stream().filter(r -> "COMPENSATE".equals(r.phase())).toList();
    }
}
