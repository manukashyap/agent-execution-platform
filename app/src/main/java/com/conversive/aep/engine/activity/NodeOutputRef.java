package com.conversive.aep.engine.activity;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * Result of one node call. The payload lives in {@code node_output}; it is also inlined here when it is
 * at most {@link #INLINE_LIMIT_BYTES}, so conditions and {@code for_each} can read it without a DB round trip.
 *
 * @param inline the output, or null when larger than the inline limit
 */
public record NodeOutputRef(
        String nodeId,
        int callIndex,
        int attempt,
        String sha256,
        int sizeBytes,
        JsonNode inline,
        BigDecimal costUsd,
        long tokens) {

    public static final int INLINE_LIMIT_BYTES = 2048;

    public NodeOutputRef {
        costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
    }

    public boolean inlined() {
        return inline != null;
    }
}
