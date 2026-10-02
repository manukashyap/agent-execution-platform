package com.conversive.aep.dryrun;

import com.conversive.aep.execution.ExecutionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * {@code GET /v1/executions/{id}/preview}: what a dry run did and would do. It carries no execution id, timestamps
 * or latencies, so two dry runs of the same workflow and input (with {@code mockLlm}) serialise byte-identically.
 *
 * @param dryRun            the execution's dry-run options
 * @param nodes             every node in definition order with its calls' outputs
 * @param mockedCalls       what the mocks stood in for, in definition order
 * @param compensationPlan  what the saga would do if the run failed now, most recent step first
 */
public record DryRunPreview(String workflowId, int version, ExecutionStatus status, JsonNode dryRun,
                            List<NodePreview> nodes, List<MockedCallView> mockedCalls,
                            List<CompensationStep> compensationPlan) {

    public DryRunPreview {
        nodes = List.copyOf(nodes);
        mockedCalls = List.copyOf(mockedCalls);
        compensationPlan = List.copyOf(compensationPlan);
    }

    public record NodePreview(String nodeId, String type, List<CallPreview> calls) {

        public NodePreview {
            calls = List.copyOf(calls);
        }
    }

    /** @param mocked true when the dry-run mocks produced {@code output}; false when the node ran live */
    public record CallPreview(int callIndex, String status, boolean mocked, JsonNode output) {
    }

    /** @param target the tool name, {@code METHOD url} or LLM model the node would have called */
    public record MockedCallView(String nodeId, int callIndex, String phase, String nodeType, String target,
                                 String argsSha256) {
    }

    /**
     * @param action      {@code COMPENSATE} (with the inverse), {@code STOP_AT_PIVOT} (an executed pivot ends the
     *                    walk) or {@code NOT_COMPENSATED} (behind an executed pivot)
     * @param inverseType node type of the inverse, for {@code COMPENSATE}
     * @param inverseTool the inverse MCP tool, when the inverse is an {@code mcp} call
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompensationStep(String nodeId, int callIndex, String action, String inverseType,
                                   String inverseTool) {
    }
}
