package com.conversive.aep.engine.workflow;

import com.conversive.aep.execution.ExecutionStatus;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Live view returned by {@link DagInterpreterWorkflow#snapshot()}.
 *
 * @param nodes forward-phase status per node id
 */
public record ExecutionSnapshot(
        ExecutionStatus status,
        Map<String, NodeStatus> nodes,
        int nodeExecutions,
        BigDecimal costUsd,
        long tokens) {

    public ExecutionSnapshot {
        Objects.requireNonNull(status, "status");
        nodes = Map.copyOf(nodes);
        costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
    }
}
