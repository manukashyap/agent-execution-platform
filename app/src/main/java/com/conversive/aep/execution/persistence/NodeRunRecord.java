package com.conversive.aep.execution.persistence;

import java.time.Instant;

/** One {@code node_run} row; {@code phase} and {@code status} are the stored text values. */
public record NodeRunRecord(
        String nodeId,
        int callIndex,
        String phase,
        int attempt,
        String status,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant endedAt) {
}
