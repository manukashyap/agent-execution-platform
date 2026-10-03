package com.conversive.aep.api.dto;

import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Priority;
import com.conversive.aep.execution.ExecutionStatus;
import com.conversive.aep.execution.persistence.ExecutionRecord;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Client view of one execution. {@code error} is null unless the run failed. */
public record ExecutionView(
        String executionId,
        String workflowId,
        int version,
        ExecutionStatus status,
        ExecutionMode mode,
        Priority priority,
        JsonNode input,
        JsonNode output,
        ErrorView error,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt,
        Instant deadlineAt) {

    public record ErrorView(String code, String message) {
    }

    public static ExecutionView of(ExecutionRecord r) {
        ErrorView error = r.errorCode() == null ? null : new ErrorView(r.errorCode(), r.errorMessage());
        return new ExecutionView(r.id().toString(), r.workflowId(), r.defVersion(), r.status(), r.mode(),
                r.priority(), r.input(), r.output(), error, r.createdAt(), r.startedAt(), r.endedAt(),
                r.deadlineAt());
    }
}
