package com.conversive.aep.router;

import com.conversive.aep.cost.BudgetService;
import com.conversive.aep.observability.AepMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RouterConfig {

    private static final Duration DECISION_HORIZON = Duration.ofSeconds(10);

    @Bean
    RoutePlanner routePlanner(RouterProperties properties, Clock clock) {
        HealthPolicy policy = HealthPolicy.defaults();
        List<ProviderRuntime> providers = properties.providers().entrySet().stream()
                .map(e -> ProviderRuntime.create(ProviderConfig.from(e.getKey(), e.getValue()), policy, clock))
                .toList();
        return new RoutePlanner(providers, properties.tenantAllowList(), properties.spill());
    }

    @Bean
    LlmRouter llmRouter(RoutePlanner planner, LlmProviderClient client, BudgetService budget, LlmCallRecorder recorder,
            RouterProperties properties, Clock clock, AepMetrics metrics) {
        return new LlmRouter(planner, client, budget, recorder, new RecentDecisions(clock, DECISION_HORIZON),
                properties.noProviderRetryDelay(), System::nanoTime, metrics);
    }
}
