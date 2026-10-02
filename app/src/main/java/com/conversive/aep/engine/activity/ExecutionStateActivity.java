package com.conversive.aep.engine.activity;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.engine.workflow.NodeStatus;
import com.conversive.aep.execution.ExecutionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;
import java.util.Set;

/** Engine-owned DB writes, run as local activities from the interpreter. */
@ActivityInterface
public interface ExecutionStateActivity {

    /** CAS of {@code workflow_execution.status}; never moves a row out of a status not in {@code from}. */
    @ActivityMethod(name = "TransitionExecution")
    TransitionResult transition(Transition transition);

    /** Records nodes the interpreter settled without an activity (skipped nodes, evaluated conditions). */
    @ActivityMethod(name = "MarkNodes")
    void markNodes(NodeMarks marks);

    /** The stored output of a node (the array of item outputs for a fan-out node), or null. */
    @ActivityMethod(name = "LoadOutput")
    JsonNode loadOutput(OutputQuery query);

    record Transition(TenantId tenantId, ExecutionId executionId, Set<ExecutionStatus> from, ExecutionStatus to,
                      String errorCode, String errorMessage, JsonNode output) {

        public Transition {
            from = Set.copyOf(from);
        }
    }

    /** @param current the row's status after the call (the new one when applied); null if the row is gone */
    record TransitionResult(boolean applied, ExecutionStatus current) {
    }

    record NodeMarks(TenantId tenantId, ExecutionId executionId, List<String> nodeIds, NodeStatus status,
                     String errorCode, String errorMessage) {

        public NodeMarks {
            nodeIds = List.copyOf(nodeIds);
        }
    }

    record OutputQuery(TenantId tenantId, ExecutionId executionId, String nodeId, boolean fanOut) {
    }
}
