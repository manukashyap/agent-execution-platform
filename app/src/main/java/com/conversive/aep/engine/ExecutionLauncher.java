package com.conversive.aep.engine;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.engine.workflow.ExecutionRequest;

/** Seam between the API and the workflow engine (Temporal in production). */
public interface ExecutionLauncher {

    /**
     * Starts the run, or attaches to it if it already exists; safe to call twice for one execution.
     *
     * @throws com.conversive.aep.common.RetryableError {@code START_FAILED} when the engine is unreachable
     * @throws RunAlreadyClosedException when the run was started earlier and has already closed
     */
    void start(ExecutionRequest request);

    /**
     * Requests cancellation.
     *
     * @return false when the engine has no open run (never started, or already closed); nothing is sent then
     */
    boolean cancel(TenantId tenantId, ExecutionId executionId);
}
