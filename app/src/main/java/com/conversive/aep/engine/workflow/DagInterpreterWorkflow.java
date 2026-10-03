package com.conversive.aep.engine.workflow;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** One Temporal workflow per execution; id {@code exec:{tenantId}:{executionId}}. */
@WorkflowInterface
public interface DagInterpreterWorkflow {

    @WorkflowMethod(name = WorkflowNames.DAG_INTERPRETER)
    ExecutionResult run(ExecutionRequest request);

    @QueryMethod
    ExecutionSnapshot snapshot();

    static String workflowId(String tenantId, String executionId) {
        return "exec:" + tenantId + ":" + executionId;
    }
}
