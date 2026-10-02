package com.conversive.aep.sideeffect;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.common.http.OutboundClient;
import com.conversive.aep.common.http.OutboundProperties;
import com.conversive.aep.common.http.OutboundRequest;
import com.conversive.aep.sideeffect.persistence.LedgerRepository;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import com.conversive.aep.observability.AepMetrics;
import com.conversive.aep.tenancy.TenantTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Shared fixture: real Postgres ledger, real {@link OutboundClient}, WireMock provider, test-controlled clock. */
abstract class LedgerTestSupport extends PostgresIntegrationTest {

    static final TenantId TENANT = TenantId.of("t_dev");
    static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");
    static final IdempotentProviderStub PROVIDER = new IdempotentProviderStub();
    static WireMockServer wireMock;

    @Autowired
    LedgerRepository ledger;

    @Autowired
    ObjectMapper mapper;

    MutableClock clock;
    SimpleMeterRegistry meters;
    LedgerSideEffectGuard guard;
    OutboundClient client;
    ExecutionId execution;

    @BeforeAll
    static void startProvider() {
        wireMock = new WireMockServer(options().dynamicPort().extensions(PROVIDER));
        wireMock.start();
    }

    @AfterAll
    static void stopProvider() {
        wireMock.stop();
    }

    @BeforeEach
    void freshFixture() {
        wireMock.resetAll();
        PROVIDER.reset(Duration.ZERO);
        clock = new MutableClock(T0);
        meters = new SimpleMeterRegistry();
        guard = new LedgerSideEffectGuard(ledger, clock, new AepMetrics(meters, tenant -> TenantTier.STANDARD));
        OutboundProperties properties = new OutboundProperties(
                List.of("localhost:" + wireMock.port()), List.of(), Duration.ofSeconds(1));
        client = new OutboundClient(properties, mapper, Clock.systemUTC());
        execution = ExecutionId.random();
    }

    EffectSpec spec(String nodeId, Phase phase, int attempt, IdempotencyMode mode, Duration startToClose) {
        return EffectSpec.of(TENANT, execution, nodeId, phase, 0, attempt, mode, startToClose);
    }

    EffectSpec forward(int attempt, IdempotencyMode mode, Duration startToClose) {
        return spec("charge", Phase.FORWARD, attempt, mode, startToClose);
    }

    /** What a node executor builds: POST with the guard's key as Idempotency-Key and the contract HTTP timeout. */
    EffectCall httpPost(String path, EffectSpec spec, JsonNode body) {
        return key -> client.send(new OutboundRequest("POST", URI.create(wireMock.baseUrl() + path), Map.of(),
                body, spec.httpTimeout(), null, ExecutionMode.LIVE, false).withIdempotencyKey(key)).body();
    }

    EffectCall charge(EffectSpec spec) {
        return httpPost("/payments/charge", spec,
                mapper.createObjectNode().put("customer_id", "c_1").put("amount_cents", 1000).put("currency", "USD"));
    }

    LedgerEntry row(EffectSpec spec) {
        return ledger.find(spec.tenantId(), spec.key()).orElseThrow();
    }
}
