package com.conversive.aep.engine.workflow;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.definition.model.FrozenDefinition.FrozenNode;
import com.conversive.aep.engine.activity.CompensationActivity;
import com.conversive.aep.engine.activity.CompensationActivity.CompensationOutcome;
import com.conversive.aep.engine.activity.CompensationActivity.CompensationTask;
import com.conversive.aep.engine.activity.CompensationActivity.Result;
import com.conversive.aep.engine.activity.ExecutionStateActivity.TransitionResult;
import com.conversive.aep.engine.workflow.DagState.StartedCall;
import com.conversive.aep.execution.ExecutionStatus;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The linear saga (06 §4.4 P2b). After a failed, cancelled or timed-out forward pass, every started call of a
 * compensatable or pivot node is reconciled and compensated in reverse start order, in a detached scope so a
 * cancel cannot interrupt it. A failing compensation does not stop the walk (continue-with-error); an executed
 * or possibly executed (unknown outcome) pivot does, because nothing before it may be rolled back.
 */
final class SagaRun {

    private record Step(FrozenNode node, int callIndex) {
    }

    private final ExecutionRequest request;
    private final DagState state;
    private final StateCalls calls;

    SagaRun(ExecutionRequest request, DagState state, StateCalls calls) {
        this.request = request;
        this.state = state;
        this.calls = calls;
    }

    /** @return the outcome to record terminally: unchanged when nothing needed compensating */
    ExecutionResult compensateIfNeeded(ExecutionResult outcome) {
        if (outcome.status() == ExecutionStatus.SUCCEEDED) {
            return outcome;
        }
        List<Step> steps = steps();
        if (steps.isEmpty()) {
            return outcome;
        }
        TransitionResult moved = calls.transition(Set.of(ExecutionStatus.RUNNING), ExecutionStatus.COMPENSATING,
                outcome.errorCode(), outcome.errorMessage(), null);
        if (!moved.applied()) {
            return outcome;
        }
        state.status(ExecutionStatus.COMPENSATING);
        List<ExecutionResult> box = new ArrayList<>(1);
        Workflow.newDetachedCancellationScope(() -> box.add(walk(outcome, steps))).run();
        return box.get(0);
    }

    /** Started calls of compensatable or pivot nodes, most recent first. */
    private List<Step> steps() {
        List<Step> steps = new ArrayList<>();
        for (StartedCall call : state.startedCalls()) {
            request.definition().node(call.nodeId())
                    .filter(node -> node.compensate() != null || node.pivot())
                    .ifPresent(node -> steps.add(new Step(node, call.callIndex())));
        }
        Collections.reverse(steps);
        return steps;
    }

    private ExecutionResult walk(ExecutionResult outcome, List<Step> steps) {
        boolean compensated = false;
        ExecutionResult failed = null;
        for (Step step : steps) {
            CompensationOutcome result;
            try {
                result = compensate(step);
            } catch (ActivityFailure e) {
                failed = firstOf(failed, failure(step, ExecutionStatus.COMPENSATION_FAILED, ForwardRun.codeOf(e),
                        "failed: " + messageOf(e)));
                continue;
            }
            if (result.result() == Result.COMPENSATED) {
                compensated = true;
            } else if (result.result() == Result.PIVOT_EXECUTED) {
                return firstOf(failed, failure(step, ExecutionStatus.COMPENSATION_FAILED, ErrorCodes.NEEDS_ATTENTION,
                        "stopped: " + result.reason()));
            } else if (result.result() == Result.NEEDS_ATTENTION) {
                // An unknown forward outcome is never compensated blindly; a human must reconcile it.
                failed = firstOf(failed, failure(step, ExecutionStatus.NEEDS_ATTENTION, ErrorCodes.NEEDS_ATTENTION,
                        "needs attention: " + result.reason()));
                if (step.node().pivot()) {
                    // The pivot may have executed, so nothing before it may be rolled back.
                    return failed;
                }
            }
        }
        if (failed != null) {
            return failed;
        }
        return compensated
                ? new ExecutionResult(ExecutionStatus.COMPENSATED, outcome.errorCode(), outcome.errorMessage())
                : outcome;
    }

    private CompensationOutcome compensate(Step step) {
        CompensationActivity stub = Workflow.newActivityStub(CompensationActivity.class,
                NodeActivityOptions.compensation(step.node()));
        CompensationTask task = new CompensationTask(NodeTasks.of(request, state, step.node(), step.callIndex()),
                step.node().compensate(), step.node().pivot());
        return stub.compensate(task);
    }

    private static ExecutionResult failure(Step step, ExecutionStatus status, String code, String detail) {
        return new ExecutionResult(status, code,
                "compensation of " + step.node().id() + " " + detail);
    }

    private static ExecutionResult firstOf(ExecutionResult first, ExecutionResult next) {
        return first != null ? first : next;
    }

    private static String messageOf(ActivityFailure e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause instanceof ApplicationFailure app ? app.getOriginalMessage()
                : cause.getMessage();
    }
}
