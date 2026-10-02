package com.conversive.aep.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.Priority;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PriorityScorerTest {

    private static final PriorityScorer.Input A = new PriorityScorer.Input("llm-a", HealthState.HEALTHY, 200, 0.010, 0);
    private static final PriorityScorer.Input B = new PriorityScorer.Input("llm-b", HealthState.HEALTHY, 500, 0.004, 0);
    private static final PriorityScorer.Input VLLM = new PriorityScorer.Input("vllm", HealthState.HEALTHY, 100, 0.002, 0);

    private final PriorityScorer scorer = new PriorityScorer();

    private static String best(Map<String, Double> scores) {
        return scores.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    @ParameterizedTest
    @CsvSource({"HIGH, llm-a", "NORMAL, llm-b", "LOW, llm-b"})
    void betweenAAndBThePriorityDecides(Priority priority, String expected) {
        assertThat(best(scorer.score(priority, List.of(A, B)))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"HIGH", "NORMAL", "LOW"})
    void aHealthyVllmIsFastestAndCheapestSoItWinsEveryPriority(Priority priority) {
        assertThat(best(scorer.score(priority, List.of(A, B, VLLM)))).isEqualTo("vllm");
    }

    @Test
    void highWeightsLatencyAndLowWeightsCost() {
        assertThat(PriorityScorer.weights(Priority.HIGH).latency()).isGreaterThan(PriorityScorer.weights(Priority.HIGH).cost());
        assertThat(PriorityScorer.weights(Priority.LOW).cost()).isGreaterThan(PriorityScorer.weights(Priority.LOW).latency());
    }

    @Test
    void degradedProviderRanksBehindEveryHealthyOne() {
        PriorityScorer.Input slowVllm = new PriorityScorer.Input("vllm", HealthState.DEGRADED, 3_000, 0.002, 0);

        Map<String, Double> scores = scorer.score(Priority.LOW, List.of(A, B, slowVllm));

        List<String> order = scores.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .toList();
        assertThat(order).containsExactly("llm-b", "llm-a", "vllm");
    }

    @Test
    void errorsMakeAProviderLessAttractive() {
        PriorityScorer.Input flakyB = new PriorityScorer.Input("llm-b", HealthState.HEALTHY, 500, 0.004, 0.4);

        assertThat(best(scorer.score(Priority.NORMAL, List.of(A, flakyB)))).isEqualTo("llm-a");
    }
}
