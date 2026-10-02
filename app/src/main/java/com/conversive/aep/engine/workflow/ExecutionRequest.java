package com.conversive.aep.engine.workflow;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.model.FrozenDefinition;
import com.conversive.aep.nodes.DryRunOptions;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Input of {@link DagInterpreterWorkflow#run}. Everything the run needs is in here, so the workflow
 * never reads the database for its definition.
 *
 * @param dryRun           options for {@code DRY_RUN}; {@link DryRunOptions#defaults()} otherwise
 * @param deadlineEpochMs  {@code workflow_execution.deadline_at}; the run times out at this instant
 */
public record ExecutionRequest(
        TenantId tenantId,
        ExecutionId executionId,
        FrozenDefinition definition,
        JsonNode input,
        ExecutionMode mode,
        DryRunOptions dryRun,
        Priority priority,
        long deadlineEpochMs) {

    public ExecutionRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(mode, "mode");
        dryRun = dryRun == null ? DryRunOptions.defaults() : dryRun;
        priority = priority == null ? Priority.NORMAL : priority;
    }
}
