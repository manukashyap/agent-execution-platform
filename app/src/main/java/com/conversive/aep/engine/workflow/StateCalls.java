package com.conversive.aep.engine.workflow;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.engine.activity.ExecutionStateActivity;
import com.conversive.aep.engine.activity.ExecutionStateActivity.NodeMarks;
import com.conversive.aep.engine.activity.ExecutionStateActivity.OutputQuery;
import com.conversive.aep.engine.activity.ExecutionStateActivity.Transition;
import com.conversive.aep.engine.activity.ExecutionStateActivity.TransitionResult;
import com.conversive.aep.execution.ExecutionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import io.temporal.workflow.Workflow;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Engine state writes as local activities, each in a detached cancellation scope: a cancelled run must
 * still record its skipped nodes and its terminal status.
 */
final class StateCalls {

    private final ExecutionStateActivity activity =
            Workflow.newLocalActivityStub(ExecutionStateActivity.class, NodeActivityOptions.state());
    private final TenantId tenantId;
    private final ExecutionId executionId;

    StateCalls(TenantId tenantId, ExecutionId executionId) {
        this.tenantId = tenantId;
        this.executionId = executionId;
    }

    TransitionResult transition(Set<ExecutionStatus> from, ExecutionStatus to, String errorCode, String errorMessage,
                                JsonNode output) {
        Transition t = new Transition(tenantId, executionId, from, to, errorCode, errorMessage, output);
        return detached(() -> activity.transition(t));
    }

    void mark(List<String> nodeIds, NodeStatus status, String errorCode, String errorMessage) {
        if (nodeIds.isEmpty()) {
            return;
        }
        NodeMarks marks = new NodeMarks(tenantId, executionId, nodeIds, status, errorCode, errorMessage);
        detached(() -> {
            activity.markNodes(marks);
            return null;
        });
    }

    JsonNode loadOutput(String nodeId, boolean fanOut) {
        OutputQuery query = new OutputQuery(tenantId, executionId, nodeId, fanOut);
        return detached(() -> activity.loadOutput(query));
    }

    private static <T> T detached(Supplier<T> call) {
        List<T> box = new ArrayList<>(1);
        Workflow.newDetachedCancellationScope(() -> box.add(call.get())).run();
        return box.get(0);
    }
}
