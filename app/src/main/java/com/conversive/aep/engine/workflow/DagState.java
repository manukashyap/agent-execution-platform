package com.conversive.aep.engine.workflow;

import com.conversive.aep.definition.model.FrozenDefinition;
import com.conversive.aep.definition.model.FrozenDefinition.FrozenNode;
import com.conversive.aep.engine.activity.NodeOutputRef;
import com.conversive.aep.execution.ExecutionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable per-run bookkeeping of the interpreter (workflow-thread only): node statuses, inlined outputs,
 * the order calls were started (for compensation) and the execution counters.
 */
final class DagState {

    /** One started activity call; kept in start order. */
    record StartedCall(String nodeId, int callIndex) {
    }

    private final FrozenDefinition definition;
    private final Map<String, NodeStatus> statuses = new LinkedHashMap<>();
    private final Map<String, JsonNode> inlineOutputs = new LinkedHashMap<>();
    private final Set<String> fanOutNodes = new HashSet<>();
    private final List<StartedCall> started = new ArrayList<>();
    private ExecutionStatus status = ExecutionStatus.QUEUED;
    private int nodeExecutions;
    private BigDecimal costUsd = BigDecimal.ZERO;
    private long tokens;

    DagState(FrozenDefinition definition) {
        this.definition = definition;
        definition.nodes().forEach(n -> statuses.put(n.id(), NodeStatus.PENDING));
    }

    FrozenDefinition definition() {
        return definition;
    }

    NodeStatus status(String nodeId) {
        return statuses.get(nodeId);
    }

    void set(String nodeId, NodeStatus nodeStatus) {
        statuses.put(nodeId, nodeStatus);
    }

    void succeeded(String nodeId, JsonNode inline) {
        statuses.put(nodeId, NodeStatus.SUCCEEDED);
        if (inline != null) {
            inlineOutputs.put(nodeId, inline);
        }
    }

    void markFanOut(String nodeId) {
        fanOutNodes.add(nodeId);
    }

    boolean isFanOut(String nodeId) {
        return fanOutNodes.contains(nodeId);
    }

    /** The inlined output, or null when the node has none or it was too large to inline. */
    JsonNode inlineOutput(String nodeId) {
        return inlineOutputs.get(nodeId);
    }

    boolean hasInlineOutput(String nodeId) {
        return inlineOutputs.containsKey(nodeId);
    }

    void callStarted(String nodeId, int callIndex) {
        started.add(new StartedCall(nodeId, callIndex));
        nodeExecutions++;
    }

    void account(NodeOutputRef ref) {
        costUsd = costUsd.add(ref.costUsd());
        tokens += ref.tokens();
    }

    List<StartedCall> startedCalls() {
        return List.copyOf(started);
    }

    int nodeExecutions() {
        return nodeExecutions;
    }

    BigDecimal costUsd() {
        return costUsd;
    }

    long tokens() {
        return tokens;
    }

    void status(ExecutionStatus newStatus) {
        this.status = newStatus;
    }

    ExecutionStatus status() {
        return status;
    }

    /** Ancestors (transitive dependencies) of {@code node} that succeeded, in definition order. */
    List<String> succeededAncestors(FrozenNode node) {
        Set<String> seen = new HashSet<>();
        List<String> stack = new ArrayList<>(node.dependsOn());
        while (!stack.isEmpty()) {
            String id = stack.remove(stack.size() - 1);
            if (seen.add(id)) {
                definition.node(id).ifPresent(dep -> stack.addAll(dep.dependsOn()));
            }
        }
        List<String> ordered = new ArrayList<>();
        for (FrozenNode n : definition.nodes()) {
            if (seen.contains(n.id()) && statuses.get(n.id()) == NodeStatus.SUCCEEDED) {
                ordered.add(n.id());
            }
        }
        return ordered;
    }

    ExecutionSnapshot snapshot() {
        return new ExecutionSnapshot(status, Map.copyOf(statuses), nodeExecutions, costUsd, tokens);
    }
}
