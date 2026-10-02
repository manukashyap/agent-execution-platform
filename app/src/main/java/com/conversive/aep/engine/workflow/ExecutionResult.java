package com.conversive.aep.engine.workflow;

import com.conversive.aep.execution.ExecutionStatus;
import java.util.Objects;

/** Final outcome of a run; error fields are null on success. P2a may extend this record. */
public record ExecutionResult(ExecutionStatus status, String errorCode, String errorMessage) {

    public ExecutionResult {
        Objects.requireNonNull(status, "status");
    }

    public static ExecutionResult succeeded() {
        return new ExecutionResult(ExecutionStatus.SUCCEEDED, null, null);
    }
}
