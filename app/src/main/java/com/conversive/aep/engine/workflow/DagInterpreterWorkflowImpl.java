package com.conversive.aep.engine.workflow;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.definition.model.FrozenDefinition.FrozenNode;
import com.conversive.aep.engine.activity.ExecutionStateActivity.TransitionResult;
import com.conversive.aep.execution.ExecutionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.temporal.workflow.Workflow;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * The DAG interpreter (06 §4.4). Status writes are engine-owned CAS local activities; the forward pass runs
 * in a child cancellation scope, and the terminal transition runs detached so a cancelled or timed-out run
 * still records its outcome. A terminal row is never overwritten (CAS on the live statuses only).
 */
public class DagInterpreterWorkflowImpl implements DagInterpreterWorkflow {

    private static final Set<ExecutionStatus> TERMINAL_FROM =
            Set.of(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING, ExecutionStatus.COMPENSATING);

    private DagState state;

    @Override
    public ExecutionResult run(ExecutionRequest request) {
        state = new DagState(request.definition());
        StateCalls calls = new StateCalls(request.tenantId(), request.executionId());
        TransitionResult started = calls.transition(Set.of(ExecutionStatus.QUEUED), ExecutionStatus.RUNNING,
                null, null, null);
        if (!started.applied() && started.current() != ExecutionStatus.RUNNING) {
            ExecutionStatus current = started.current() == null ? ExecutionStatus.FAILED : started.current();
            state.status(current);
            return new ExecutionResult(current, null, "execution row is " + current + "; not started");
        }
        state.status(ExecutionStatus.RUNNING);
        List<ExecutionResult> box = new ArrayList<>(1);
        Workflow.newCancellationScope(() -> box.add(new ForwardRun(request, state, calls).run())).run();
        ExecutionResult outcome = box.get(0);
        settleLeftovers(calls);
        return finish(request, calls, outcome);
    }

    @Override
    public ExecutionSnapshot snapshot() {
        return state == null
                ? new ExecutionSnapshot(ExecutionStatus.QUEUED, Map.of(), 0, null, 0)
                : state.snapshot();
    }

    private void settleLeftovers(StateCalls calls) {
        List<String> pending = new ArrayList<>();
        List<String> running = new ArrayList<>();
        for (FrozenNode node : state.definition().nodes()) {
            if (state.status(node.id()) == NodeStatus.PENDING) {
                pending.add(node.id());
            } else if (state.status(node.id()) == NodeStatus.RUNNING) {
                running.add(node.id());
            }
        }
        pending.forEach(id -> state.set(id, NodeStatus.SKIPPED));
        running.forEach(id -> state.set(id, NodeStatus.CANCELLED));
        calls.mark(pending, NodeStatus.SKIPPED, null, null);
        calls.mark(running, NodeStatus.CANCELLED, ErrorCodes.CANCELLED, null);
    }

    private ExecutionResult finish(ExecutionRequest request, StateCalls calls, ExecutionResult outcome) {
        ExecutionResult result = new SagaRun(request, state, calls).compensateIfNeeded(outcome);
        JsonNode output = result.status() == ExecutionStatus.SUCCEEDED ? output() : null;
        TransitionResult end = calls.transition(TERMINAL_FROM, result.status(), result.errorCode(),
                result.errorMessage(), output);
        if (!end.applied() && end.current() != null && end.current().isTerminal()) {
            state.status(end.current());
            return new ExecutionResult(end.current(), result.errorCode(), result.errorMessage());
        }
        state.status(result.status());
        return result;
    }

    /** Outputs of the sink nodes (no dependants) that succeeded; non-inlined ones as a stored marker. */
    private JsonNode output() {
        Set<String> hasDependants = new HashSet<>();
        state.definition().nodes().forEach(n -> hasDependants.addAll(n.dependsOn()));
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (FrozenNode node : state.definition().nodes()) {
            if (!hasDependants.contains(node.id()) && state.status(node.id()) == NodeStatus.SUCCEEDED) {
                JsonNode inline = state.inlineOutput(node.id());
                out.set(node.id(), inline != null ? inline
                        : JsonNodeFactory.instance.objectNode().put("$stored", "node_output"));
            }
        }
        return out;
    }
}
