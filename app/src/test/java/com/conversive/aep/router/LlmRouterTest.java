package com.conversive.aep.router;

import static com.conversive.aep.router.RouterFixtures.byName;
import static com.conversive.aep.router.RouterFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.cost.BudgetService;
import com.conversive.aep.cost.Reservation;
import com.conversive.aep.observability.AepMetrics;
import com.conversive.aep.tenancy.TenantTier;
import com.conversive.aep.support.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class LlmRouterTest {

    private static final Duration WINDOW = HealthPolicy.defaults().window();
    /** 50 req/s: the 5 % DEGRADED probe share then yields 25 samples per window, over the 20-sample minimum. */
    private static final Duration GAP = Duration.ofMillis(20);

    private final MutableClock clock = MutableClock.atWindowStart();
    private final AtomicLong nanos = new AtomicLong();
    private final FakeClient client = new FakeClient();
    private final CountingBudget budget = new CountingBudget();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AepMetrics metrics = new AepMetrics(registry, tenant -> TenantTier.STANDARD);
    private final List<LlmCallRecord> rows = new ArrayList<>();
    private final LlmCallRecorder recorder = new LlmCallRecorder() {
        @Override
        public void record(LlmCallRecord record) {
            rows.add(record);
        }

        @Override
        public List<LlmCallRecord> findByExecution(TenantId tenantId, ExecutionId executionId) {
            return rows.stream().filter(r -> r.executionId().equals(executionId)).toList();
        }
    };

    private List<ProviderRuntime> providers = RouterFixtures.providers(clock);

    private LlmRouter router() {
        return new LlmRouter(RouterFixtures.planner(providers), client, budget, recorder,
                new RecentDecisions(clock, WINDOW), Duration.ofSeconds(1), nanos::get, metrics);
    }

    /** Provider behaviour: a latency (charged to the fake ticker) and an optional error. */
    private final class FakeClient implements LlmProviderClient {
        private final Map<String, Long> latencyMs = new ConcurrentHashMap<>();
        private final Map<String, Function<String, RuntimeException>> errors = new ConcurrentHashMap<>();

        @Override
        public Reply complete(ProviderConfig provider, Call call) {
            nanos.addAndGet(Duration.ofMillis(latencyMs.getOrDefault(provider.name(), 50L)).toNanos());
            Function<String, RuntimeException> error = errors.get(provider.name());
            if (error != null) {
                throw error.apply(provider.name());
            }
            return new Reply("hi from " + provider.name(), List.of(), "stop", 10, 5);
        }
    }

    private static final class CountingBudget implements BudgetService {
        private final AtomicInteger reserved = new AtomicInteger();
        private final AtomicInteger confirmed = new AtomicInteger();
        private final AtomicInteger cancelled = new AtomicInteger();
        private final List<String> refs = new ArrayList<>();

        @Override
        public Reservation tryReserve(TenantId tenantId, ExecutionId executionId, BigDecimal estimateUsd, String ref) {
            reserved.incrementAndGet();
            refs.add(ref);
            return new Reservation(ref, tenantId, estimateUsd);
        }

        @Override
        public void confirm(Reservation reservation, BigDecimal actualUsd) {
            confirmed.incrementAndGet();
        }

        @Override
        public void cancel(Reservation reservation) {
            cancelled.incrementAndGet();
        }
    }

    /** Sends one NORMAL request every {@link #GAP} for one window and counts (provider, reason) of the answers. */
    private Map<String, Long> driveWindow(LlmRouter router) {
        List<LlmResponse> responses = new ArrayList<>();
        for (long t = 0; t < WINDOW.toMillis(); t += GAP.toMillis()) {
            responses.add(router.route(request(Priority.NORMAL)));
            clock.advance(GAP);
        }
        return responses.stream().collect(Collectors.groupingBy(r -> r.provider() + "/" + r.reason(), Collectors.counting()));
    }

    private HealthState stateOf(LlmRouter router, String provider) {
        return router.health().stream().filter(v -> v.provider().equals(provider)).findFirst().orElseThrow().state();
    }

    @Test
    void slowVllmDegradesAfterTwoWindowsAndTrafficMovesToB() {
        LlmRouter router = router();
        client.latencyMs.put("vllm", 100L);
        assertThat(driveWindow(router)).containsExactly(Map.entry("vllm/best_score", 500L));

        client.latencyMs.put("vllm", 3_000L);
        assertThat(driveWindow(router)).containsExactly(Map.entry("vllm/best_score", 500L));
        assertThat(stateOf(router, "vllm")).isEqualTo(HealthState.HEALTHY);
        assertThat(driveWindow(router)).containsExactly(Map.entry("vllm/best_score", 500L));
        assertThat(stateOf(router, "vllm")).isEqualTo(HealthState.DEGRADED);

        assertThat(driveWindow(router)).containsOnly(Map.entry("llm-b/best_score", 475L), Map.entry("vllm/probe", 25L));
        assertThat(router.recentDecisions().counts()).containsKeys("llm-b", "vllm");
    }

    @Test
    void degradedVllmRecoversThroughProbesAndTakesTrafficBack() {
        LlmRouter router = router();
        client.latencyMs.put("vllm", 3_000L);
        driveWindow(router);
        driveWindow(router);
        driveWindow(router);
        assertThat(stateOf(router, "vllm")).isEqualTo(HealthState.DEGRADED);

        client.latencyMs.put("vllm", 100L);
        for (int i = 0; i < 3; i++) {
            assertThat(driveWindow(router)).containsOnly(Map.entry("llm-b/best_score", 475L), Map.entry("vllm/probe", 25L));
        }

        assertThat(stateOf(router, "vllm")).isEqualTo(HealthState.HEALTHY);
        assertThat(driveWindow(router)).containsExactly(Map.entry("vllm/best_score", 500L));
    }

    @Test
    void sparseTrafficNeverDegrades() {
        LlmRouter router = router();
        client.latencyMs.put("vllm", 3_000L);
        for (int w = 0; w < 4; w++) {
            for (int i = 0; i < 19; i++) {
                assertThat(router.route(request(Priority.NORMAL)).provider()).isEqualTo("vllm");
            }
            clock.advance(WINDOW);
        }
        assertThat(stateOf(router, "vllm")).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    void fallsBackToTheNextProviderOn5xx() {
        client.errors.put("vllm", name -> new RetryableError(ErrorCodes.UPSTREAM_UNAVAILABLE, name + " 503"));

        LlmResponse response = router().route(request(Priority.NORMAL));

        assertThat(response.provider()).isEqualTo("llm-b");
        assertThat(response.reason()).isEqualTo(RouteReasons.FALLBACK_AFTER_ERROR);
        assertThat(response.attempts()).extracting(LlmResponse.ProviderAttempt::provider).containsExactly("vllm", "llm-b");
        assertThat(rows).extracting(LlmCallRecord::seq).containsExactly(0, 1);
        assertThat(rows.get(0).outcome()).isEqualTo(LlmCallRecord.FAILED);
        assertThat(rows.get(0).errorCode()).isEqualTo(ErrorCodes.UPSTREAM_UNAVAILABLE);
        assertThat(rows.get(0).reason()).isEqualTo(RouteReasons.BEST_SCORE);
        assertThat(rows.get(1).outcome()).isEqualTo(LlmCallRecord.SUCCEEDED);
        assertThat(rows.get(1).costUsd()).isEqualByComparingTo("0.000060");
        assertThat(budget.reserved.get()).isEqualTo(2);
        assertThat(budget.cancelled.get()).isEqualTo(1);
        assertThat(budget.confirmed.get()).isEqualTo(1);
        assertThat(budget.refs).containsExactly("llm:n1:0:1:0:0", "llm:n1:0:1:0:1");
        assertThat(registry.get(AepMetrics.ROUTER_DECISIONS).tags(AepMetrics.TAG_PROVIDER, "llm-b",
                AepMetrics.TAG_REASON, RouteReasons.FALLBACK_AFTER_ERROR).counter().count()).isEqualTo(1);
        assertThat(registry.get(AepMetrics.PROVIDER_ERRORS).tags(AepMetrics.TAG_PROVIDER, "vllm",
                AepMetrics.TAG_ERROR_CLASS, ErrorCodes.UPSTREAM_UNAVAILABLE).counter().count()).isEqualTo(1);
        assertThat(registry.get(AepMetrics.LLM_TOKENS).tags(AepMetrics.TAG_PROVIDER, "llm-b",
                AepMetrics.TAG_DIRECTION, AepMetrics.DIRECTION_PROMPT).counter().count()).isEqualTo(10);
        assertThat(registry.get(AepMetrics.LLM_COST_USD).tags(AepMetrics.TAG_PROVIDER, "llm-b").counter().count())
                .isEqualTo(0.00006, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(registry.get(AepMetrics.LLM_LATENCY).tags(AepMetrics.TAG_OUTCOME, AepMetrics.OUTCOME_FAILED)
                .timer().count()).isEqualTo(1);
    }

    @Test
    void failsRetryablyWithLlmUnavailableWhenEveryProviderFails() {
        for (String p : List.of("llm-a", "llm-b", "vllm")) {
            client.errors.put(p, name -> new RetryableError(ErrorCodes.UPSTREAM_TIMEOUT, name + " timed out"));
        }

        assertThatThrownBy(() -> router().route(request(Priority.NORMAL)))
                .isInstanceOfSatisfying(RetryableError.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.LLM_UNAVAILABLE));
        assertThat(rows).hasSize(3).allSatisfy(r -> assertThat(r.outcome()).isEqualTo(LlmCallRecord.FAILED));
    }

    @Test
    void aNonRetryableProviderErrorStopsWithoutFallback() {
        client.errors.put("vllm", name -> new NonRetryableError(ErrorCodes.UPSTREAM_CLIENT_ERROR, "400"));

        assertThatThrownBy(() -> router().route(request(Priority.NORMAL)))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_CLIENT_ERROR));
        assertThat(rows).hasSize(1);
        assertThat(stateOf(router(), "vllm")).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    void noProviderIsRetryableWithDelayWhenEveryBucketIsEmpty() {
        providers = RouterFixtures.providers(clock, 1, 1, 1);
        LlmRouter router = router();
        router.route(request(Priority.NORMAL));
        router.route(request(Priority.NORMAL));
        router.route(request(Priority.NORMAL));

        assertThatThrownBy(() -> router.route(request(Priority.NORMAL)))
                .isInstanceOfSatisfying(RetryableError.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCodes.NO_PROVIDER_AVAILABLE);
                    assertThat(e.nextRetryDelay()).isEqualTo(Duration.ofSeconds(1));
                });
    }

    @Test
    void noProviderIsNonRetryableWhenNothingCanEverServeTheRequest() {
        assertThatThrownBy(() -> router().route(request(RouterFixtures.TENANT, Priority.NORMAL, Set.of("vision"), null)))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.NO_PROVIDER_AVAILABLE));
        assertThat(rows).isEmpty();
    }

    @Test
    void stopsTryingFallbacksOnceTheTimeoutIsSpent() {
        client.latencyMs.put("vllm", 31_000L);
        client.errors.put("vllm", name -> new RetryableError(ErrorCodes.UPSTREAM_TIMEOUT, "slow"));

        assertThatThrownBy(() -> router().route(request(Priority.NORMAL)))
                .isInstanceOfSatisfying(RetryableError.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.LLM_UNAVAILABLE));
        assertThat(rows).hasSize(1);
    }

    @Test
    void halfOpenProbeSlotIsReleasedWhenTheProbeEndsWithoutAHealthOutcome() {
        ProviderRuntime vllm = byName(providers, "vllm");
        RouterFixtures.open(vllm, clock);
        clock.advance(Duration.ofSeconds(30));
        client.errors.put("vllm", name -> new NonRetryableError(ErrorCodes.UPSTREAM_CLIENT_ERROR, "400"));
        LlmRouter router = router();

        for (int i = 0; i < 12; i++) {
            assertThatThrownBy(() -> router.route(request(Priority.NORMAL))).isInstanceOf(NonRetryableError.class);
        }

        assertThat(vllm.health().tryAcquireProbe()).isTrue();
    }
}
