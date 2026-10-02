package com.conversive.aep.execution;

/** Execution status, exactly the 06 §0 vocabulary (and the {@code workflow_execution_status_chk} values). */
public enum ExecutionStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_FAILED,
    NEEDS_ATTENTION,
    START_FAILED;

    /** True once no further transition is expected; QUEUED, RUNNING and COMPENSATING are live. */
    public boolean isTerminal() {
        return this != QUEUED && this != RUNNING && this != COMPENSATING;
    }
}
