package com.conversive.aep.observability.trace;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /v1/executions/{id}/trace}: one execution's timeline. Nodes are ordered by first start; each groups
 * its attempts (node_run), LLM provider calls (llm_call), tool calls (tool_call_audit) and ledger rows.
 */
public record ExecutionTrace(UUID executionId, String workflowId, int version, String mode, String status,
                             String errorCode, Instant startedAt, Instant endedAt, Long durationMs, Totals totals,
                             List<NodeTrace> nodes) {

    public ExecutionTrace {
        nodes = List.copyOf(nodes);
    }

    /**
     * @param retries      node attempts beyond the first, summed over nodes
     * @param llmFallbacks provider calls after the first within one LLM request (router fallbacks)
     */
    public record Totals(int nodes, int attempts, int retries, int llmCalls, int llmFallbacks, long promptTokens,
                         long completionTokens, long totalTokens, BigDecimal costUsd, int toolCalls, Budget budget) {
    }

    /** @param limitUsd the execution's cap; null when nothing was ever reserved */
    public record Budget(BigDecimal limitUsd, BigDecimal reservedUsd, BigDecimal confirmedUsd,
                         int cancelledReservations) {
    }

    /** One node invocation: a node id, its forEach call index and the phase (FORWARD or COMPENSATE). */
    public record NodeTrace(String nodeId, String type, int callIndex, String phase, String status, int attempts,
                            int retries, Instant startedAt, Instant endedAt, Long durationMs, String errorCode,
                            List<AttemptTrace> attemptHistory, List<LlmCallTrace> llmCalls,
                            List<ToolCallTrace> toolCalls, List<SideEffectTrace> sideEffects) {

        public NodeTrace {
            attemptHistory = List.copyOf(attemptHistory);
            llmCalls = List.copyOf(llmCalls);
            toolCalls = List.copyOf(toolCalls);
            sideEffects = List.copyOf(sideEffects);
        }
    }

    public record AttemptTrace(int attempt, String status, Instant startedAt, Instant endedAt, Long durationMs,
                               String errorCode) {
    }

    public record LlmCallTrace(int attempt, int turn, int seq, String provider, String model, String reason,
                               String outcome, String errorCode, int promptTokens, int completionTokens,
                               BigDecimal costUsd, long latencyMs, Instant at) {
    }

    public record ToolCallTrace(int attempt, String tool, String outcome, String errorCode, long latencyMs,
                                Instant at) {
    }

    public record SideEffectTrace(String state, String idempotencyMode, int ownerAttempt, String externalRef,
                                  Instant updatedAt) {
    }
}
