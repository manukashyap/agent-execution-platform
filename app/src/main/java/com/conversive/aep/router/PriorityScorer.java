package com.conversive.aep.router;

import com.conversive.aep.common.Priority;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Priority-weighted score, lower is better: latency and cost are normalised to the largest value among
 * the candidates, error rate is used as is, and a DEGRADED provider gets a flat penalty larger than any
 * healthy score. HIGH weights latency, LOW weights cost, NORMAL is balanced with a slight cost lean so
 * that A and B (mirror images on latency/cost) don't tie, matching the 01 §9 spill direction.
 */
public final class PriorityScorer {

    public static final double DEGRADED_PENALTY = 1.0;

    public record Weights(double latency, double cost, double errors) {
    }

    /** Scoring inputs; {@code latencyMs} is the nominal latency, or the observed p95 for a DEGRADED provider. */
    public record Input(String provider, HealthState state, double latencyMs, double costPerThousand, double errorRate) {
    }

    public static Weights weights(Priority priority) {
        return switch (priority) {
            case HIGH -> new Weights(0.7, 0.1, 0.2);
            case NORMAL -> new Weights(0.35, 0.45, 0.2);
            case LOW -> new Weights(0.1, 0.7, 0.2);
        };
    }

    public Map<String, Double> score(Priority priority, List<Input> inputs) {
        Weights w = weights(priority);
        double maxLatency = inputs.stream().mapToDouble(Input::latencyMs).max().orElse(1);
        double maxCost = inputs.stream().mapToDouble(Input::costPerThousand).max().orElse(1);
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Input in : inputs) {
            double score = w.latency() * ratio(in.latencyMs(), maxLatency)
                    + w.cost() * ratio(in.costPerThousand(), maxCost)
                    + w.errors() * in.errorRate();
            if (in.state() == HealthState.DEGRADED) {
                score += DEGRADED_PENALTY;
            }
            scores.put(in.provider(), score);
        }
        return Map.copyOf(scores);
    }

    private static double ratio(double value, double max) {
        return max <= 0 ? 0 : value / max;
    }
}
