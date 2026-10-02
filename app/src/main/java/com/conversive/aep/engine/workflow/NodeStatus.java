package com.conversive.aep.engine.workflow;

/** Node status per phase (06 §0); same values as {@code node_run_status_chk}. */
public enum NodeStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED,
    NEEDS_ATTENTION
}
