package com.conversive.aep.router;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.support.MutableClock;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The three providers of application.yml (llm-a, llm-b, vllm) with configurable rate limits. */
final class RouterFixtures {

    static final TenantId TENANT = TenantId.of("t_test");
    static final RouterProperties.Spill SPILL = new RouterProperties.Spill("vllm", "llm-a", "llm-b");

    private RouterFixtures() {
    }

    static List<ProviderRuntime> providers(Clock clock, int rpsA, int rpsB, int rpsVllm) {
        HealthPolicy policy = HealthPolicy.defaults();
        return List.of(
                ProviderRuntime.create(config("llm-a", "model-a", 200, "0.010", rpsA, Set.of("chat", "tools", "json")), policy, clock),
                ProviderRuntime.create(config("llm-b", "model-b", 500, "0.004", rpsB, Set.of("chat", "tools", "json")), policy, clock),
                ProviderRuntime.create(config("vllm", "vllm-default", 100, "0.002", rpsVllm, Set.of("chat", "json")), policy, clock));
    }

    static List<ProviderRuntime> providers(Clock clock) {
        return providers(clock, 1_000, 1_000, 1_000);
    }

    static RoutePlanner planner(List<ProviderRuntime> providers) {
        return planner(providers, Map.of());
    }

    static RoutePlanner planner(List<ProviderRuntime> providers, Map<String, List<String>> allowList) {
        return new RoutePlanner(providers, allowList, SPILL);
    }

    static ProviderRuntime byName(List<ProviderRuntime> providers, String name) {
        return providers.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    static LlmRequest request(Priority priority) {
        return request(TENANT, priority, Set.of("chat"), null);
    }

    static LlmRequest request(TenantId tenant, Priority priority, Set<String> capabilities, String modelHint) {
        return new LlmRequest(tenant, ExecutionId.random(), "n1", 0, 1, 0, priority, capabilities,
                List.of(LlmMessage.user("hello")), List.of(), modelHint, Duration.ofSeconds(30), ExecutionMode.LIVE);
    }

    /** Drives the tracker to OPEN with one window of failures (the clock moves one window). */
    static void open(ProviderRuntime provider, MutableClock clock) {
        for (int i = 0; i < 20; i++) {
            provider.health().record(100, false);
        }
        clock.advance(HealthPolicy.defaults().window());
        provider.health().state();
    }

    /** Drives the tracker to DEGRADED with two slow windows (the clock moves two windows). */
    static void degrade(ProviderRuntime provider, MutableClock clock) {
        for (int w = 0; w < 2; w++) {
            for (int i = 0; i < 20; i++) {
                provider.health().record(3_000, true);
            }
            clock.advance(HealthPolicy.defaults().window());
            provider.health().state();
        }
    }

    private static ProviderConfig config(String name, String model, long latencyMs, String cost, int rps,
            Set<String> capabilities) {
        return new ProviderConfig(name, URI.create("http://localhost:1/llm/" + name), model, Duration.ofMillis(latencyMs),
                new BigDecimal(cost), rps, capabilities);
    }
}
