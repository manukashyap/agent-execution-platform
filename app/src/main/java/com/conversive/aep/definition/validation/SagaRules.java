package com.conversive.aep.definition.validation;

import static com.conversive.aep.definition.validation.ValidationCodes.COMPENSATABLE_AFTER_PIVOT;
import static com.conversive.aep.definition.validation.ValidationCodes.MULTIPLE_PIVOTS_ON_PATH;
import static com.conversive.aep.definition.validation.ValidationCodes.SIDE_EFFECT_WITHOUT_COMPENSATION;

import com.conversive.aep.definition.NodeTraits;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Saga-shape warnings (05): a pivot is the point of no return, so compensatable work belongs before
 * it, and a path should have at most one pivot. Runs only on acyclic graphs.
 */
final class SagaRules {

    private SagaRules() {
    }

    static void check(List<String> order, DefinitionGraph graph, Map<String, NodeTraits> traits, Findings out) {
        Map<String, Integer> pivotsUpTo = new HashMap<>();
        for (String id : order) {
            NodeTraits node = traits.get(id);
            int pivotsBefore = graph.dependencies(id).stream().filter(pivotsUpTo::containsKey)
                    .mapToInt(pivotsUpTo::get).max().orElse(0);
            pivotsUpTo.put(id, pivotsBefore + (node.pivot() ? 1 : 0));
            if (node.compensatable() && !node.pivot() && pivotsBefore > 0) {
                out.warning(COMPENSATABLE_AFTER_PIVOT, id,
                        "compensatable node runs after a pivot; it cannot be rolled back once the pivot commits");
            }
            if (node.pivot() && pivotsBefore > 0) {
                out.warning(MULTIPLE_PIVOTS_ON_PATH, id, "more than one pivot on a path");
            }
            if (node.sideEffecting() && !node.compensatable() && !node.pivot() && !node.retriable()) {
                out.warning(SIDE_EFFECT_WITHOUT_COMPENSATION, id,
                        "side-effecting node has neither compensate nor pivot");
            }
        }
    }
}
