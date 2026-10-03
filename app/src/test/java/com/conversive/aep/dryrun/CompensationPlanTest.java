package com.conversive.aep.dryrun;

import static com.conversive.aep.dryrun.DryRunFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.definition.model.FrozenDefinition;
import com.conversive.aep.definition.model.FrozenDefinition.Compensation;
import com.conversive.aep.definition.model.FrozenDefinition.ExecutionLimits;
import com.conversive.aep.definition.model.FrozenDefinition.FrozenNode;
import com.conversive.aep.dryrun.DryRunPreview.CompensationStep;
import com.conversive.aep.execution.persistence.NodeRunRecord;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T6.2: the plan mirrors the saga walk: newest first, an executed pivot stops it. */
class CompensationPlanTest {

    private static final Compensation DELETE = new Compensation("mcp", json("{\"tool\":\"crm.delete\"}"));

    @Test
    void compensatesNewestFirstAndStopsAtAnExecutedPivot() {
        FrozenDefinition def = definition(
                node("reserve", DELETE, false), node("fetch", null, false),
                node("charge", null, true), node("crm", DELETE, false));
        List<NodeRunRecord> runs = List.of(run("reserve", 0, "SUCCEEDED"), run("fetch", 0, "SUCCEEDED"),
                run("charge", 0, "SUCCEEDED"), run("crm", 0, "SUCCEEDED"), run("crm", 1, "FAILED"));

        List<CompensationStep> plan = CompensationPlan.render(def, runs);

        assertThat(plan).containsExactly(
                new CompensationStep("crm", 1, CompensationPlan.COMPENSATE, "mcp", "crm.delete"),
                new CompensationStep("crm", 0, CompensationPlan.COMPENSATE, "mcp", "crm.delete"),
                new CompensationStep("charge", 0, CompensationPlan.STOP_AT_PIVOT, null, null),
                new CompensationStep("reserve", 0, CompensationPlan.NOT_COMPENSATED, null, null));
    }

    @Test
    void aPivotThatNeverSucceededDoesNotStopTheWalkAndUnstartedNodesAreSkipped() {
        FrozenDefinition def = definition(node("reserve", DELETE, false), node("send", null, true),
                node("later", DELETE, false));
        List<NodeRunRecord> runs = List.of(run("reserve", 0, "SUCCEEDED"), run("send", 0, "FAILED"),
                new NodeRunRecord("reserve", 0, "COMPENSATE", 1, "SUCCEEDED", null, null, null, null));

        assertThat(CompensationPlan.render(def, runs)).containsExactly(
                new CompensationStep("send", 0, CompensationPlan.NOT_COMPENSATED, null, null),
                new CompensationStep("reserve", 0, CompensationPlan.COMPENSATE, "mcp", "crm.delete"));
    }

    private static FrozenDefinition definition(FrozenNode... nodes) {
        return new FrozenDefinition("wf", 1, "sha", List.of(nodes), 60, 16,
                new ExecutionLimits(BigDecimal.ONE, 1000, 100, 100));
    }

    private static FrozenNode node(String id, Compensation compensate, boolean pivot) {
        return new FrozenNode(id, "mcp", json("{}"), List.of(), null, null, 30, 60, compensate != null || pivot,
                compensate, pivot, null);
    }

    private static NodeRunRecord run(String nodeId, int callIndex, String status) {
        return new NodeRunRecord(nodeId, callIndex, "FORWARD", 1, status, null, null, null, null);
    }
}
