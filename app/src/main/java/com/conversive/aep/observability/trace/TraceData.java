package com.conversive.aep.observability.trace;

import com.conversive.aep.observability.persistence.TraceRows.BudgetRow;
import com.conversive.aep.observability.persistence.TraceRows.ExecutionRow;
import com.conversive.aep.observability.persistence.TraceRows.LlmCallRow;
import com.conversive.aep.observability.persistence.TraceRows.NodeRunRow;
import com.conversive.aep.observability.persistence.TraceRows.SideEffectRow;
import com.conversive.aep.observability.persistence.TraceRows.ToolCallRow;
import java.util.List;
import java.util.Map;

/** Everything one trace is built from, already scoped to the caller's tenant. */
public record TraceData(ExecutionRow execution, Map<String, String> nodeTypes, List<NodeRunRow> nodeRuns,
                        List<LlmCallRow> llmCalls, List<ToolCallRow> toolCalls, List<SideEffectRow> sideEffects,
                        BudgetRow budget) {

    public TraceData {
        nodeTypes = Map.copyOf(nodeTypes);
        nodeRuns = List.copyOf(nodeRuns);
        llmCalls = List.copyOf(llmCalls);
        toolCalls = List.copyOf(toolCalls);
        sideEffects = List.copyOf(sideEffects);
    }
}
