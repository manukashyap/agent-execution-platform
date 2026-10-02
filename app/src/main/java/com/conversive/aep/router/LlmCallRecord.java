package com.conversive.aep.router;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import java.math.BigDecimal;
import java.util.List;

/** One {@code llm_call} row: a single provider call. {@code seq} orders the calls of one route (0 = primary). */
public record LlmCallRecord(
        TenantId tenantId,
        ExecutionId executionId,
        String nodeId,
        int callIndex,
        int attempt,
        int turn,
        int seq,
        String provider,
        String model,
        Priority priority,
        List<Candidate> candidates,
        String reason,
        int promptTokens,
        int completionTokens,
        BigDecimal costUsd,
        long latencyMs,
        String outcome,
        String errorCode) {

    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    public LlmCallRecord {
        candidates = List.copyOf(candidates);
        costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
    }
}
