package com.conversive.aep.router;

import static com.conversive.aep.router.RouterFixtures.byName;
import static com.conversive.aep.router.RouterFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RoutePlannerTest {

    private final MutableClock clock = MutableClock.atWindowStart();

    private static List<String> order(RoutePlan plan) {
        return plan.steps().stream().map(s -> s.provider().name()).toList();
    }

    private static String exclusion(RoutePlan plan, String provider) {
        return plan.candidates().stream().filter(c -> c.provider().equals(provider)).findFirst().orElseThrow().excludedBy();
    }

    @Test
    void healthyVllmIsTheBestScoreWithTheOthersAsFallbacks() {
        RoutePlan plan = RouterFixtures.planner(RouterFixtures.providers(clock)).plan(request(Priority.NORMAL));

        assertThat(order(plan)).containsExactly("vllm", "llm-b", "llm-a");
        assertThat(plan.steps().get(0).reason()).isEqualTo(RouteReasons.BEST_SCORE);
        assertThat(plan.steps().get(1).reason()).isEqualTo(RouteReasons.FALLBACK_AFTER_ERROR);
        assertThat(plan.candidates()).allSatisfy(c -> assertThat(c.score()).isNotNull());
    }

    @Test
    void excludesProvidersMissingARequestedCapability() {
        RoutePlan plan = RouterFixtures.planner(RouterFixtures.providers(clock))
                .plan(request(RouterFixtures.TENANT, Priority.HIGH, Set.of("chat", "tools"), null));

        assertThat(order(plan)).containsExactly("llm-a", "llm-b");
        assertThat(exclusion(plan, "vllm")).isEqualTo(RouteReasons.EXCLUDED_CAPABILITY);
    }

    @Test
    void excludesProvidersTheTenantIsNotAllowedToUse() {
        RoutePlanner planner = RouterFixtures.planner(RouterFixtures.providers(clock), Map.of("t_strict", List.of("llm-a")));

        RoutePlan strict = planner.plan(request(TenantId.of("t_strict"), Priority.LOW, Set.of("chat"), null));
        RoutePlan other = planner.plan(request(Priority.LOW));

        assertThat(order(strict)).containsExactly("llm-a");
        assertThat(exclusion(strict, "llm-b")).isEqualTo(RouteReasons.EXCLUDED_TENANT);
        assertThat(exclusion(strict, "vllm")).isEqualTo(RouteReasons.EXCLUDED_TENANT);
        assertThat(order(other)).hasSize(3);
    }

    @Test
    void excludesOpenProviders() {
        List<ProviderRuntime> providers = RouterFixtures.providers(clock);
        RouterFixtures.open(byName(providers, "vllm"), clock);

        RoutePlan plan = RouterFixtures.planner(providers).plan(request(Priority.NORMAL));

        assertThat(order(plan)).containsExactly("llm-b", "llm-a");
        assertThat(exclusion(plan, "vllm")).isEqualTo(RouteReasons.EXCLUDED_OPEN);
        assertThat(plan.hasTransientExclusion()).isTrue();
    }

    @Test
    void spillsToBWhenTheVllmBucketIsEmptyAndToAForHighPriority() {
        List<ProviderRuntime> providers = RouterFixtures.providers(clock, 1_000, 1_000, 2);
        TokenBucket vllm = byName(providers, "vllm").bucket();
        while (vllm.tryAcquire()) {
            // drain
        }
        RoutePlanner planner = RouterFixtures.planner(providers);

        RoutePlan normal = planner.plan(request(Priority.NORMAL));
        RoutePlan low = planner.plan(request(Priority.LOW));
        RoutePlan high = planner.plan(request(Priority.HIGH));

        assertThat(order(normal)).containsExactly("llm-b", "llm-a");
        assertThat(normal.steps().get(0).reason()).isEqualTo(RouteReasons.SPILL_BUCKET_EMPTY);
        assertThat(exclusion(normal, "vllm")).isEqualTo(RouteReasons.EXCLUDED_BUCKET);
        assertThat(order(low).get(0)).isEqualTo("llm-b");
        assertThat(order(high)).containsExactly("llm-a", "llm-b");
        assertThat(high.steps().get(0).reason()).isEqualTo(RouteReasons.SPILL_BUCKET_EMPTY);

        clock.advance(Duration.ofSeconds(1));
        assertThat(order(planner.plan(request(Priority.NORMAL))).get(0)).isEqualTo("vllm");
    }

    @Test
    void degradedVllmGetsEveryTwentiethRequestAsAProbe() {
        List<ProviderRuntime> providers = RouterFixtures.providers(clock);
        RouterFixtures.degrade(byName(providers, "vllm"), clock);
        RoutePlanner planner = RouterFixtures.planner(providers);

        List<RoutePlan> plans = IntStream.range(0, 40).mapToObj(i -> planner.plan(request(Priority.NORMAL))).toList();

        assertThat(plans.get(0).steps().get(0).provider().name()).isEqualTo("llm-b");
        assertThat(order(plans.get(0))).containsExactly("llm-b", "llm-a", "vllm");
        List<Integer> probes = IntStream.range(0, 40)
                .filter(i -> RouteReasons.PROBE.equals(plans.get(i).steps().get(0).reason()))
                .boxed().toList();
        assertThat(probes).containsExactly(19, 39);
        assertThat(order(plans.get(19))).containsExactly("vllm", "llm-b", "llm-a");
    }

    @Test
    void halfOpenProviderGetsTheRequestAsAProbe() {
        List<ProviderRuntime> providers = RouterFixtures.providers(clock);
        RouterFixtures.open(byName(providers, "vllm"), clock);
        clock.advance(Duration.ofSeconds(30));

        RoutePlan plan = RouterFixtures.planner(providers).plan(request(Priority.HIGH));

        assertThat(plan.steps().get(0).provider().name()).isEqualTo("vllm");
        assertThat(plan.steps().get(0).reason()).isEqualTo(RouteReasons.HALF_OPEN_PROBE);
        assertThat(plan.steps().get(0).halfOpenProbe()).isTrue();
        assertThat(order(plan)).containsExactly("vllm", "llm-a", "llm-b");
    }

    @Test
    void halfOpenProviderIsExcludedOnceItsProbeSlotsAreTaken() {
        List<ProviderRuntime> providers = RouterFixtures.providers(clock);
        RouterFixtures.open(byName(providers, "vllm"), clock);
        clock.advance(Duration.ofSeconds(30));
        RoutePlanner planner = RouterFixtures.planner(providers);
        IntStream.range(0, 10).forEach(i -> planner.plan(request(Priority.NORMAL)));

        RoutePlan plan = planner.plan(request(Priority.NORMAL));

        assertThat(order(plan)).containsExactly("llm-b", "llm-a");
        assertThat(exclusion(plan, "vllm")).isEqualTo(RouteReasons.EXCLUDED_HALF_OPEN_BUSY);
    }

    @Test
    void modelHintPrefersTheProviderServingThatModel() {
        RoutePlan plan = RouterFixtures.planner(RouterFixtures.providers(clock))
                .plan(request(RouterFixtures.TENANT, Priority.NORMAL, Set.of("chat"), "model-a"));

        assertThat(order(plan)).containsExactly("llm-a", "vllm", "llm-b");
        assertThat(plan.steps().get(0).reason()).isEqualTo(RouteReasons.MODEL_HINT);
    }

    @Test
    void noStepsWhenNothingOffersTheCapability() {
        RoutePlan plan = RouterFixtures.planner(RouterFixtures.providers(clock))
                .plan(request(RouterFixtures.TENANT, Priority.NORMAL, Set.of("vision"), null));

        assertThat(plan.steps()).isEmpty();
        assertThat(plan.hasTransientExclusion()).isFalse();
    }
}
