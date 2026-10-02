package com.conversive.aep.observability.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read-only rows behind {@code /trace}; one record per source table. */
public final class TraceRows {

    private TraceRows() {
    }

    public record ExecutionRow(UUID id, String workflowId, int version, String mode, String status,
                               String errorCode, Instant startedAt, Instant endedAt) {
    }

    public record NodeRunRow(String nodeId, int callIndex, String phase, int attempt, String status,
                             String errorCode, Instant startedAt, Instant endedAt) {
    }

    public record LlmCallRow(String nodeId, int callIndex, int attempt, int turn, int seq, String provider,
                             String model, String reason, int promptTokens, int completionTokens, BigDecimal costUsd,
                             long latencyMs, String outcome, String errorCode, Instant at) {
    }

    public record ToolCallRow(String nodeId, int callIndex, String phase, int attempt, String tool, String outcome,
                              String errorCode, long latencyMs, Instant at) {
    }

    public record SideEffectRow(String nodeId, int callIndex, String phase, String state, String idempotencyMode,
                                int ownerAttempt, String externalRef, Instant updatedAt) {
    }

    /**
     * @param limitUsd              the execution's cap; null when no reservation was ever made
     * @param reservedUsd           still held by RESERVED rows
     * @param confirmedUsd          actual spend of CONFIRMED rows
     * @param cancelledReservations reservations released without spend (failed provider calls)
     */
    public record BudgetRow(BigDecimal limitUsd, BigDecimal reservedUsd, BigDecimal confirmedUsd,
                            int cancelledReservations) {
    }
}
