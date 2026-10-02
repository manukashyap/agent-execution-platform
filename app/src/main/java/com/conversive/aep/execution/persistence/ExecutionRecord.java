package com.conversive.aep.execution.persistence;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.execution.ExecutionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** One {@code workflow_execution} row. JSON columns are null when unset. */
public record ExecutionRecord(
        ExecutionId id,
        TenantId tenantId,
        String workflowId,
        int defVersion,
        ExecutionMode mode,
        ExecutionStatus status,
        Priority priority,
        long rowVersion,
        String idempotencyKey,
        JsonNode input,
        JsonNode options,
        JsonNode output,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant deadlineAt,
        Instant endedAt,
        Instant createdAt,
        Instant updatedAt) {
}
