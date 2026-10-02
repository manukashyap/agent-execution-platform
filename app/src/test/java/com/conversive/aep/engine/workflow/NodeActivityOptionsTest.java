package com.conversive.aep.engine.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.definition.model.FrozenDefinition.Compensation;
import com.conversive.aep.definition.model.FrozenDefinition.FrozenNode;
import com.conversive.aep.definition.model.OnFailure;
import com.conversive.aep.engine.activity.CompensationActivity;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.temporal.activity.ActivityOptions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class NodeActivityOptionsTest {

    private static FrozenNode sideEffectingNode(int timeoutS) {
        return new FrozenNode("charge", "mcp", JsonNodeFactory.instance.objectNode(), List.of(), null, null,
                timeoutS, timeoutS, true, new Compensation("mcp", JsonNodeFactory.instance.objectNode()), false,
                OnFailure.FAIL_FAST);
    }

    @Test
    void compensationStartToCloseCoversEveryReconcileRoundPlusTheInverse() {
        int timeoutS = 30;

        ActivityOptions options = NodeActivityOptions.compensation(sideEffectingNode(timeoutS));

        Duration calls = Duration.ofSeconds((long) (CompensationActivity.MAX_RECONCILE_ROUNDS + 1) * timeoutS);
        assertThat(options.getStartToCloseTimeout())
                .isGreaterThan(calls)
                .isLessThanOrEqualTo(calls.plus(NodeActivityOptions.COMPENSATION_SLACK));
    }

    @Test
    void forwardStartToCloseStaysTheNodeTimeout() {
        ActivityOptions options = NodeActivityOptions.forNode(sideEffectingNode(30));

        assertThat(options.getStartToCloseTimeout()).isEqualTo(Duration.ofSeconds(30));
    }
}
