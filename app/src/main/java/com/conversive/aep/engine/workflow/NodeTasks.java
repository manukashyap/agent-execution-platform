package com.conversive.aep.engine.workflow;

import com.conversive.aep.definition.model.FrozenDefinition;
import com.conversive.aep.definition.model.FrozenDefinition.FrozenNode;
import com.conversive.aep.engine.activity.NodeTask;
import com.conversive.aep.engine.activity.NodeTask.UpstreamRef;
import java.util.List;

/** Builds the {@link NodeTask} of one node call; shared by the forward pass and the saga. */
final class NodeTasks {

    private NodeTasks() {
    }

    /** Upstream = succeeded non-condition ancestors, so a compensation sees the same input as its forward. */
    static NodeTask of(ExecutionRequest request, DagState state, FrozenNode node, int callIndex) {
        FrozenDefinition definition = request.definition();
        List<UpstreamRef> upstream = state.succeededAncestors(node).stream()
                .filter(id -> definition.node(id).map(n -> !ForwardRun.CONDITION.equals(n.type())).orElse(false))
                .map(id -> new UpstreamRef(id, state.isFanOut(id)))
                .toList();
        String itemsPath = node.forEach() == null ? null : node.forEach().items();
        return new NodeTask(request.tenantId(), request.executionId(), definition.workflowId(),
                definition.version(), node.id(), node.type(), callIndex, node.sideEffecting(), node.config(),
                upstream, itemsPath, node.timeoutS(), request.mode(), request.priority(), request.dryRun());
    }
}
