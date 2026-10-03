package com.conversive.aep.execution.service;

import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Priority;
import com.conversive.aep.nodes.DryRunOptions;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * A validated request to start {@code workflowId}.
 *
 * @param version        null means the latest published version
 * @param idempotencyKey the client's {@code Idempotency-Key}, or a server-generated one
 */
public record StartCommand(String workflowId, Integer version, JsonNode input, ExecutionMode mode,
                           DryRunOptions dryRun, Priority priority, String idempotencyKey) {

    public StartCommand {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        mode = mode == null ? ExecutionMode.LIVE : mode;
        priority = priority == null ? Priority.NORMAL : priority;
        dryRun = dryRun == null ? DryRunOptions.defaults() : dryRun;
    }
}
