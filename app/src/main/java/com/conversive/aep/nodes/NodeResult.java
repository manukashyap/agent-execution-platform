package com.conversive.aep.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;

public record NodeResult(JsonNode output, BigDecimal costUsd, long tokens, Map<String, String> meta) {

    public NodeResult {
        costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public static NodeResult of(JsonNode output) {
        return new NodeResult(output, BigDecimal.ZERO, 0, Map.of());
    }
}
