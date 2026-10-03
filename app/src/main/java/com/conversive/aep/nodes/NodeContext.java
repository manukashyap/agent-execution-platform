package com.conversive.aep.nodes;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

public record NodeContext(
        TenantId tenantId,
        ExecutionId executionId,
        String workflowId,
        int defVersion,
        String nodeId,
        String nodeType,
        int callIndex,
        int attempt,
        Phase phase,
        ExecutionMode mode,
        boolean sideEffecting,
        JsonNode config,
        JsonNode input,
        Duration startToClose,
        Priority priority,
        DryRunOptions dryRun) {
}
