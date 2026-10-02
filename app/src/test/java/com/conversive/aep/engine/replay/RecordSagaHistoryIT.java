package com.conversive.aep.engine.replay;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.engine.EngineHarness;
import com.conversive.aep.execution.ExecutionStatus;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.service.ExecutionService;
import com.conversive.aep.support.InProcessTemporal;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Re-records {@code histories/saga-compensates.json} (charge, failing send, refund). Disabled unless
 * {@code AEP_RECORD_HISTORIES=true}: {@code AEP_RECORD_HISTORIES=true ./gradlew :app:test --tests '*Record*HistoryIT'}.
 */
@EnabledIfEnvironmentVariable(named = HistoryFiles.RECORD_ENV, matches = "true")
@Import(InProcessTemporal.class)
class RecordSagaHistoryIT extends PostgresIntegrationTest {

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
    WorkflowClient client;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;

    @Test
    void recordsARunThatCompensates() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/charge")).willReturn(okJson("{\"charge_id\":\"ch_1\"}")));
        WIRE_MOCK.stubFor(post(urlEqualTo("/refund")).willReturn(okJson("{\"refunded\":true}")));
        WIRE_MOCK.stubFor(post(urlEqualTo("/send")).willReturn(aResponse().withStatus(500)));
        EngineHarness h = new EngineHarness(new TestTenants(jdbc).create("t_replay_saga"), definitions, executions,
                repository, client, jdbc, mapper);
        String base = WIRE_MOCK.baseUrl();
        h.publish("""
                {"workflow_id":"replay_saga","version":1,"nodes":[
                  {"id":"charge","type":"http","side_effecting":true,
                   "config":{"method":"POST","url":"%s/charge","body":{"amount":42}},
                   "compensate":{"type":"http","config":{"method":"POST","url":"%s/refund",
                                 "body":{"charge_id":"{{forward.body.charge_id}}"}}}},
                  {"id":"send","type":"http","depends_on":["charge"],
                   "retry":{"max_attempts":2,"initial_interval_ms":20},
                   "config":{"method":"POST","url":"%s/send"}}]}
                """.formatted(base, base, base));
        ExecutionId id = h.start("replay_saga");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.COMPENSATED);
        HistoryFiles.record(client, h.tenant(), id, HistoryFiles.SAGA);
    }
}
