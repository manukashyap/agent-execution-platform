package com.conversive.aep.engine.activity;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.nodes.DryRunOptions;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * One node call scheduled by the interpreter ({@link NodeActivity#run}). Upstream outputs are passed by
 * reference and loaded by the activity, so the workflow history stays small.
 *
 * @param callIndex  0, or the item index of a {@code for_each} node
 * @param upstream   succeeded ancestors whose outputs form the node input (keyed by node id)
 * @param itemsPath  the {@code for_each.items} path; the activity resolves item {@code callIndex} from it
 * @param timeoutS   the node's StartToClose
 */
public record NodeTask(
        TenantId tenantId,
        ExecutionId executionId,
        String workflowId,
        int defVersion,
        String nodeId,
        String nodeType,
        int callIndex,
        boolean sideEffecting,
        JsonNode config,
        List<UpstreamRef> upstream,
        String itemsPath,
        int timeoutS,
        ExecutionMode mode,
        Priority priority,
        DryRunOptions dryRun) {

    public NodeTask {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(nodeId, "nodeId");
        upstream = upstream == null ? List.of() : List.copyOf(upstream);
    }

    /** @param fanOut the node ran {@code for_each}; its output is the array of item outputs */
    public record UpstreamRef(String nodeId, boolean fanOut) {
    }
}
