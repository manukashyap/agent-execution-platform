package com.conversive.aep.dryrun;

import com.conversive.aep.common.Phase;
import com.conversive.aep.definition.NodeTraits;
import com.conversive.aep.definition.model.FrozenDefinition;
import com.conversive.aep.definition.model.FrozenDefinition.FrozenNode;
import com.conversive.aep.dryrun.DryRunPreview.CompensationStep;
import com.conversive.aep.execution.persistence.NodeRunRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Renders the saga the engine would run if the execution failed now (the {@code SagaRun} walk): every started call
 * of a compensatable or pivot node, most recent first; an executed pivot stops the walk. "Most recent" is reverse
 * definition order then call index, which is a valid start order and, unlike timestamps, stable across runs.
 */
final class CompensationPlan {

    static final String COMPENSATE = "COMPENSATE";
    static final String STOP_AT_PIVOT = "STOP_AT_PIVOT";
    static final String NOT_COMPENSATED = "NOT_COMPENSATED";
    private static final String SUCCEEDED = "SUCCEEDED";

    private CompensationPlan() {
    }

    static List<CompensationStep> render(FrozenDefinition definition, List<NodeRunRecord> runs) {
        Map<String, TreeSet<Integer>> started = new HashMap<>();
        Set<String> succeeded = new HashSet<>();
        for (NodeRunRecord run : runs) {
            if (Phase.FORWARD.name().equals(run.phase())) {
                started.computeIfAbsent(run.nodeId(), k -> new TreeSet<>()).add(run.callIndex());
                if (SUCCEEDED.equals(run.status())) {
                    succeeded.add(run.nodeId() + "#" + run.callIndex());
                }
            }
        }
        List<CompensationStep> plan = new ArrayList<>();
        boolean stopped = false;
        for (FrozenNode node : definition.nodes().reversed()) {
            if (node.compensate() == null && !node.pivot()) {
                continue;
            }
            for (int callIndex : started.getOrDefault(node.id(), new TreeSet<>()).descendingSet()) {
                CompensationStep step = step(node, callIndex, stopped, succeeded.contains(node.id() + "#" + callIndex));
                stopped = stopped || STOP_AT_PIVOT.equals(step.action());
                plan.add(step);
            }
        }
        return plan;
    }

    private static CompensationStep step(FrozenNode node, int callIndex, boolean stopped, boolean executed) {
        if (stopped) {
            return new CompensationStep(node.id(), callIndex, NOT_COMPENSATED, null, null);
        }
        if (node.pivot() && executed) {
            return new CompensationStep(node.id(), callIndex, STOP_AT_PIVOT, null, null);
        }
        if (node.compensate() == null) {
            return new CompensationStep(node.id(), callIndex, NOT_COMPENSATED, null, null);
        }
        return new CompensationStep(node.id(), callIndex, COMPENSATE, node.compensate().type(),
                NodeTraits.toolName(node.compensate().config()));
    }
}
