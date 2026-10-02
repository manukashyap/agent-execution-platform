package com.conversive.aep.engine.workflow;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.definition.model.FrozenDefinition;
import com.conversive.aep.definition.model.FrozenDefinition.ExecutionLimits;
import com.conversive.aep.definition.model.FrozenDefinition.FrozenNode;
import com.conversive.aep.definition.model.OnFailure;
import com.conversive.aep.engine.activity.NodeActivity;
import com.conversive.aep.engine.activity.NodeOutputRef;
import com.conversive.aep.engine.workflow.condition.Condition;
import com.conversive.aep.engine.workflow.condition.ConditionEvaluator;
import com.conversive.aep.engine.workflow.condition.ValuePath;
import com.conversive.aep.execution.ExecutionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The forward pass of one execution (06 §4.4 P2a): ready set, up to {@code maxParallel} node activities,
 * a {@code Promise.anyOf} loop, conditions in-workflow, {@code for_each} fan-out, deadline and counters.
 * Runs inside its own cancellation scope; an abort cancels that scope and drains what is in flight.
 */
final class ForwardRun {

    static final int DEFAULT_MAX_PARALLEL = 16;
    static final String CONDITION = "condition";

    private record Call(String nodeId, int callIndex) {
    }

    private final ExecutionRequest request;
    private final FrozenDefinition definition;
    private final DagState state;
    private final StateCalls calls;
    private final int maxParallel;
    private final Map<String, NodeActivity> stubs = new HashMap<>();
    private final Map<Promise<NodeOutputRef>, Call> inflight = new LinkedHashMap<>();
    private final Map<String, FanOut> fanOuts = new LinkedHashMap<>();
    private Promise<Void> deadline;
    private ExecutionResult abort;

    ForwardRun(ExecutionRequest request, DagState state, StateCalls calls) {
        this.request = request;
        this.definition = request.definition();
        this.state = state;
        this.calls = calls;
        this.maxParallel = definition.maxParallel() > 0 ? definition.maxParallel() : DEFAULT_MAX_PARALLEL;
    }

    ExecutionResult run() {
        deadline = startDeadline();
        checkInterrupts();
        scheduleReady();
        while (!inflight.isEmpty()) {
            awaitAny();
            checkInterrupts();
            collectCompleted();
            scheduleReady();
        }
        return abort != null ? abort : ExecutionResult.succeeded();
    }

    private Promise<Void> startDeadline() {
        if (request.deadlineEpochMs() <= 0) {
            return null;
        }
        long remaining = Math.max(1, request.deadlineEpochMs() - Workflow.currentTimeMillis());
        return Workflow.newTimer(Duration.ofMillis(remaining));
    }

    private void awaitAny() {
        List<Promise<?>> waits = new ArrayList<>(inflight.keySet());
        if (deadline != null && abort == null) {
            waits.add(deadline);
        }
        try {
            Promise.anyOf(waits.toArray(new Promise<?>[0])).get();
        } catch (RuntimeException failed) {
            // A failed promise only wakes the loop; each one is inspected in collectCompleted.
        }
    }

    private void checkInterrupts() {
        if (abort != null) {
            return;
        }
        if (deadline != null && deadline.isCompleted() && deadline.getFailure() == null) {
            abortWith(ExecutionStatus.TIMED_OUT, ErrorCodes.TIMEOUT, "execution deadline reached");
        } else if (CancellationScope.current().isCancelRequested()) {
            abort = new ExecutionResult(ExecutionStatus.CANCELLED, ErrorCodes.CANCELLED, "execution cancelled");
            haltFanOuts();
        }
    }

    private void abortWith(ExecutionStatus status, String code, String message) {
        if (abort != null) {
            return;
        }
        abort = new ExecutionResult(status, code, message);
        haltFanOuts();
        CancellationScope.current().cancel(message);
    }

    private void haltFanOuts() {
        fanOuts.values().forEach(f -> f.halt(ErrorCodes.CANCELLED, abort.errorMessage()));
    }

    // ---- completions ----

    private void collectCompleted() {
        List<Promise<NodeOutputRef>> done = inflight.keySet().stream().filter(Promise::isCompleted).toList();
        for (Promise<NodeOutputRef> promise : done) {
            Call call = inflight.remove(promise);
            RuntimeException failure = promise.getFailure();
            if (failure == null) {
                onSuccess(call, promise.get());
            } else {
                onFailure(call, failure);
            }
        }
    }

    private void onSuccess(Call call, NodeOutputRef ref) {
        state.account(ref);
        FanOut fanOut = fanOuts.get(call.nodeId());
        if (fanOut == null) {
            state.succeeded(call.nodeId(), ref.inline());
        } else {
            fanOut.succeeded(call.callIndex(), ref.inline());
            settleFanOut(fanOut);
        }
        checkBudgets();
    }

    private void onFailure(Call call, RuntimeException failure) {
        String code = codeOf(failure);
        String message = messageOf(failure);
        FanOut fanOut = fanOuts.get(call.nodeId());
        if (fanOut == null) {
            nodeFailed(definition.node(call.nodeId()).orElseThrow(), code, message, !raisedByActivity(failure));
        } else {
            fanOut.failed(code, message);
            if (!ErrorCodes.CANCELLED.equals(code) && fanOut.node().onFailure() != OnFailure.CONTINUE) {
                abortWith(ExecutionStatus.FAILED, code, "node " + call.nodeId() + "[" + call.callIndex()
                        + "] failed: " + message);
            }
            settleFanOut(fanOut);
        }
    }

    private void settleFanOut(FanOut fanOut) {
        if (!fanOut.settled()) {
            return;
        }
        String nodeId = fanOut.node().id();
        fanOuts.remove(nodeId);
        if (fanOut.failureCode() == null) {
            state.succeeded(nodeId, fanOut.inlineArray());
        } else {
            nodeFailed(fanOut.node(), fanOut.failureCode(), fanOut.failureMessage(), true);
        }
    }

    /** @param record write the node_run row here (no activity recorded this failure itself) */
    private void nodeFailed(FrozenNode node, String code, String message, boolean record) {
        NodeStatus status = ErrorCodes.CANCELLED.equals(code) ? NodeStatus.CANCELLED : NodeStatus.FAILED;
        state.set(node.id(), status);
        if (record) {
            calls.mark(List.of(node.id()), status, code, message);
        }
        if (status == NodeStatus.FAILED && node.onFailure() != OnFailure.CONTINUE) {
            abortWith(ExecutionStatus.FAILED, code, "node " + node.id() + " failed: " + message);
        }
    }

    private void checkBudgets() {
        ExecutionLimits limits = definition.limits();
        if (limits.maxCostUsd() != null && state.costUsd().compareTo(limits.maxCostUsd()) > 0) {
            abortWith(ExecutionStatus.FAILED, ErrorCodes.BUDGET_EXCEEDED,
                    "cost " + state.costUsd() + " exceeds max_cost_usd " + limits.maxCostUsd());
        } else if (limits.maxTokens() > 0 && state.tokens() > limits.maxTokens()) {
            abortWith(ExecutionStatus.FAILED, ErrorCodes.TOKEN_BUDGET_EXCEEDED,
                    "tokens " + state.tokens() + " exceed max_tokens " + limits.maxTokens());
        }
    }

    static String codeOf(Throwable failure) {
        Throwable cause = failure instanceof ActivityFailure a && a.getCause() != null ? a.getCause() : failure;
        if (cause instanceof ApplicationFailure app && app.getType() != null) {
            return app.getType();
        }
        if (cause instanceof TimeoutFailure) {
            return ErrorCodes.TIMEOUT;
        }
        if (cause instanceof CanceledFailure) {
            return ErrorCodes.CANCELLED;
        }
        return ErrorCodes.INTERNAL;
    }

    /**
     * The activity's own catch block recorded the failure only when it raised it; a timeout or a dead worker
     * leaves its node_run row RUNNING for the interpreter to close.
     */
    private static boolean raisedByActivity(Throwable failure) {
        Throwable cause = failure instanceof ActivityFailure a && a.getCause() != null ? a.getCause() : failure;
        return cause instanceof ApplicationFailure;
    }

    private static String messageOf(Throwable failure) {
        Throwable cause = failure instanceof ActivityFailure a && a.getCause() != null ? a.getCause() : failure;
        if (cause instanceof ApplicationFailure app) {
            return app.getOriginalMessage();
        }
        return cause.getMessage();
    }

    // ---- scheduling ----

    private void scheduleReady() {
        boolean progressed = true;
        while (progressed && abort == null) {
            progressed = false;
            for (FrozenNode node : definition.nodes()) {
                if (abort == null && state.status(node.id()) == NodeStatus.PENDING && depsSettled(node)) {
                    progressed |= start(node);
                }
            }
            progressed |= startFanOutItems();
        }
    }

    private boolean depsSettled(FrozenNode node) {
        return node.dependsOn().stream().allMatch(dep -> isSettled(state.status(dep)));
    }

    private static boolean isSettled(NodeStatus status) {
        return status != NodeStatus.PENDING && status != NodeStatus.RUNNING;
    }

    private boolean shouldRun(FrozenNode node) {
        boolean anyBroken = node.dependsOn().stream().map(state::status)
                .anyMatch(s -> s == NodeStatus.FAILED || s == NodeStatus.CANCELLED || s == NodeStatus.NEEDS_ATTENTION);
        boolean anySucceeded = node.dependsOn().stream().map(state::status).anyMatch(s -> s == NodeStatus.SUCCEEDED);
        return !anyBroken && (node.dependsOn().isEmpty() || anySucceeded);
    }

    /** @return whether the node left PENDING */
    private boolean start(FrozenNode node) {
        if (!shouldRun(node)) {
            skip(List.of(node.id()));
            return true;
        }
        if (CONDITION.equals(node.type())) {
            evaluateCondition(node);
            return true;
        }
        if (node.forEach() != null) {
            startFanOut(node);
            return true;
        }
        if (inflight.size() >= maxParallel || !launch(node, 0)) {
            return false;
        }
        state.set(node.id(), NodeStatus.RUNNING);
        return true;
    }

    private void skip(List<String> nodeIds) {
        nodeIds.forEach(id -> state.set(id, NodeStatus.SKIPPED));
        calls.mark(nodeIds, NodeStatus.SKIPPED, null, null);
    }

    private void evaluateCondition(FrozenNode node) {
        boolean result;
        try {
            result = ConditionEvaluator.evaluate(Condition.parse(node.config()), this::valueOf);
        } catch (RuntimeException e) {
            nodeFailed(node, ErrorCodes.VALIDATION_FAILED, "condition failed: " + e.getMessage(), true);
            return;
        }
        Condition condition = Condition.parse(node.config());
        List<String> untaken = (result ? condition.elseNodes() : condition.thenNodes()).stream()
                .filter(id -> state.status(id) == NodeStatus.PENDING).toList();
        state.succeeded(node.id(), JsonNodeFactory.instance.objectNode().put("result", result));
        calls.mark(List.of(node.id()), NodeStatus.SUCCEEDED, null, null);
        skip(untaken);
    }

    private JsonNode valueOf(String root) {
        if (ValuePath.INPUT.equals(root)) {
            return request.input();
        }
        if (state.hasInlineOutput(root)) {
            return state.inlineOutput(root);
        }
        if (state.status(root) == NodeStatus.SUCCEEDED) {
            JsonNode loaded = calls.loadOutput(root, state.isFanOut(root));
            return loaded == null ? MissingNode.getInstance() : loaded;
        }
        return MissingNode.getInstance();
    }

    private void startFanOut(FrozenNode node) {
        ValuePath path = ValuePath.parse(node.forEach().items());
        JsonNode items = path.resolve(valueOf(path.root()));
        state.markFanOut(node.id());
        if (!items.isArray()) {
            nodeFailed(node, ErrorCodes.VALIDATION_FAILED, "for_each.items " + path + " is not an array", true);
        } else if (items.size() > definition.limits().maxFanout()) {
            nodeFailed(node, ErrorCodes.FANOUT_LIMIT, "for_each expands to " + items.size()
                    + " items, limit " + definition.limits().maxFanout(), true);
        } else if (items.isEmpty()) {
            state.succeeded(node.id(), JsonNodeFactory.instance.arrayNode());
            calls.mark(List.of(node.id()), NodeStatus.SUCCEEDED, null, null);
        } else {
            fanOuts.put(node.id(), new FanOut(node, items.size(), node.forEach().maxConcurrency()));
            state.set(node.id(), NodeStatus.RUNNING);
        }
    }

    private boolean startFanOutItems() {
        boolean started = false;
        for (FanOut fanOut : List.copyOf(fanOuts.values())) {
            while (abort == null && fanOut.canStart() && inflight.size() < maxParallel
                    && launch(fanOut.node(), fanOut.nextIndex())) {
                fanOut.started();
                started = true;
            }
        }
        return started;
    }

    /** Schedules one node activity; false (and an abort) when the node-execution cap is reached. */
    private boolean launch(FrozenNode node, int callIndex) {
        int cap = definition.limits().maxNodeExecutions();
        if (cap > 0 && state.nodeExecutions() >= cap) {
            abortWith(ExecutionStatus.FAILED, ErrorCodes.NODE_EXEC_LIMIT,
                    "more than " + cap + " node executions");
            return false;
        }
        state.callStarted(node.id(), callIndex);
        NodeActivity stub = stubs.computeIfAbsent(node.id(),
                id -> Workflow.newActivityStub(NodeActivity.class, NodeActivityOptions.forNode(node)));
        inflight.put(Async.function(stub::run, NodeTasks.of(request, state, node, callIndex)),
                new Call(node.id(), callIndex));
        return true;
    }
}
