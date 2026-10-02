package com.conversive.aep.execution.persistence;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

/** A row to insert as {@code QUEUED}. */
public record NewExecution(
        ExecutionId id,
        TenantId tenantId,
        String workflowId,
        int defVersion,
        ExecutionMode mode,
        Priority priority,
        String idempotencyKey,
        JsonNode input,
        JsonNode options,
        Instant createdAt,
        Instant deadlineAt) {

    public NewExecution {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
    }
}
