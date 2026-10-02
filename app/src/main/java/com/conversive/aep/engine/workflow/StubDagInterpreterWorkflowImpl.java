package com.conversive.aep.engine.workflow;

import com.conversive.aep.execution.ExecutionStatus;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P1 placeholder: completes immediately without calling any activity. P2a replaces it with the
 * real DAG interpreter.
 */
public class StubDagInterpreterWorkflowImpl implements DagInterpreterWorkflow {

    private ExecutionSnapshot snapshot =
            new ExecutionSnapshot(ExecutionStatus.QUEUED, Map.of(), 0, BigDecimal.ZERO, 0);

    @Override
    public ExecutionResult run(ExecutionRequest request) {
        Map<String, NodeStatus> nodes = new LinkedHashMap<>();
        request.definition().nodes().forEach(node -> nodes.put(node.id(), NodeStatus.SKIPPED));
        snapshot = new ExecutionSnapshot(ExecutionStatus.SUCCEEDED, nodes, 0, BigDecimal.ZERO, 0);
        return ExecutionResult.succeeded();
    }

    @Override
    public ExecutionSnapshot snapshot() {
        return snapshot;
    }
}
