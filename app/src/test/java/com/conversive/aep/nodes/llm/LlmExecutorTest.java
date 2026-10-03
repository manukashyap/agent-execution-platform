package com.conversive.aep.nodes.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.cost.NoOpBudgetService;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.router.HealthPolicy;
import com.conversive.aep.router.LlmCallRecord;
import com.conversive.aep.router.LlmCallRecorder;
import com.conversive.aep.router.LlmProviderClient;
import com.conversive.aep.router.LlmRouter;
import com.conversive.aep.router.ProviderConfig;
import com.conversive.aep.router.ProviderRuntime;
import com.conversive.aep.router.RecentDecisions;
import com.conversive.aep.router.RoutePlanner;
import com.conversive.aep.router.RouterProperties;
import com.conversive.aep.observability.AepMetrics;
import com.conversive.aep.tenancy.TenantTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LlmExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<LlmProviderClient.Call> calls = new ArrayList<>();
    private final List<LlmCallRecord> rows = new ArrayList<>();
    private final LlmExecutor executor = new LlmExecutor(router(), MAPPER);

    private LlmRouter router() {
        Clock clock = Clock.systemUTC();
        ProviderConfig config = new ProviderConfig("llm-a", URI.create("http://localhost:1"), "model-a",
                Duration.ofMillis(200), new BigDecimal("0.010"), 100, Set.of("chat", "json"));
        RoutePlanner planner = new RoutePlanner(List.of(ProviderRuntime.create(config, HealthPolicy.defaults(), clock)),
                Map.of(), new RouterProperties.Spill("vllm", "llm-a", "llm-b"));
        LlmProviderClient client = (provider, call) -> {
            calls.add(call);
            return new LlmProviderClient.Reply("[1,2]", List.of(), "stop", 3, 2);
        };
        LlmCallRecorder recorder = new LlmCallRecorder() {
            @Override
            public void record(LlmCallRecord record) {
                rows.add(record);
            }

            @Override
            public List<LlmCallRecord> findByExecution(TenantId tenantId, ExecutionId executionId) {
                return rows;
            }
        };
        return new LlmRouter(planner, client, new NoOpBudgetService(), recorder,
                new RecentDecisions(clock, Duration.ofSeconds(10)), Duration.ofSeconds(1), System::nanoTime,
                new AepMetrics(new SimpleMeterRegistry(), tenant -> TenantTier.STANDARD));
    }

    private static NodeContext context(String configJson, Duration startToClose, Priority priority) throws Exception {
        return new NodeContext(TenantId.of("t_unit"), ExecutionId.random(), "wf", 1, "n1", LlmExecutor.TYPE, 2, 3,
                Phase.FORWARD, ExecutionMode.LIVE, false, MAPPER.readTree(configJson), MAPPER.readTree("{\"x\":\"X\"}"),
                startToClose, priority, null);
    }

    @Test
    void buildsMessagesFromSystemAndMessagesConfig() throws Exception {
        var result = executor.execute(context("""
                {"system":"sys {{x}}","messages":[{"role":"user","content":"u {{x}}"},{"role":"assistant","content":"a"}],
                 "capability":["json"]}
                """, Duration.ofSeconds(10), null));

        assertThat(calls.get(0).messages()).extracting(m -> m.role() + ":" + m.content())
                .containsExactly("system:sys X", "user:u X", "assistant:a");
        assertThat(calls.get(0).timeout()).isLessThanOrEqualTo(Duration.ofSeconds(9));
        assertThat(result.output().path("json").isArray()).isTrue();
        assertThat(rows.get(0).priority()).isEqualTo(Priority.NORMAL);
        assertThat(rows.get(0).callIndex()).isEqualTo(2);
        assertThat(rows.get(0).attempt()).isEqualTo(3);
    }

    @Test
    void configPriorityOverridesTheExecutionPriority() throws Exception {
        executor.execute(context("{\"prompt\":\"p\",\"priority\":\"low\"}", null, Priority.HIGH));

        assertThat(rows.get(0).priority()).isEqualTo(Priority.LOW);
    }

    @Test
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> executor.execute(context("{\"system\":\"only\"}", null, null)))
                .isInstanceOfSatisfying(NonRetryableError.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED));
        assertThatThrownBy(() -> executor.execute(context("{\"prompt\":\"p\",\"priority\":\"urgent\"}", null, null)))
                .isInstanceOfSatisfying(NonRetryableError.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED));
        assertThatThrownBy(() -> executor.execute(context("{\"prompt\":\"p\",\"tools\":[\"crm\"]}", null, null)))
                .isInstanceOfSatisfying(NonRetryableError.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED));
        assertThat(calls).isEmpty();
    }
}
