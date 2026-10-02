package com.conversive.aep.engine.activity;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.Failures;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.Reversibility;
import com.conversive.aep.definition.model.FrozenDefinition.Compensation;
import com.conversive.aep.engine.persistence.NodeRunRepository;
import com.conversive.aep.engine.persistence.NodeRunRepository.RunKey;
import com.conversive.aep.engine.workflow.NodeStatus;
import com.conversive.aep.nodes.ExecutorRegistry;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.sideeffect.CompensationDecision;
import com.conversive.aep.sideeffect.CompensationDecision.Kind;
import com.conversive.aep.sideeffect.CompensationReconciler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.failure.ApplicationFailure;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** {@link CompensationActivity}: reconciler decision, bounded forward reconciliation, then the guarded inverse. */
@Component
public class CompensationActivityImpl implements CompensationActivity {

    /** RECONCILE_FORWARD rounds before the outcome is declared unresolvable. */
    static final int MAX_RECONCILE_ROUNDS = 2;

    private static final Logger log = LoggerFactory.getLogger(CompensationActivityImpl.class);

    private final CompensationReconciler reconciler;
    private final ExecutorRegistry executors;
    private final NodeInputAssembler inputs;
    private final NodeRunRepository runs;
    private final HeartbeatingRunner runner;
    private final Clock clock;
    private final ActivityTelemetry telemetry;

    public CompensationActivityImpl(CompensationReconciler reconciler, ExecutorRegistry executors,
                                    NodeInputAssembler inputs, NodeRunRepository runs, HeartbeatingRunner runner,
                                    Clock clock, ActivityTelemetry telemetry) {
        this.reconciler = reconciler;
        this.executors = executors;
        this.inputs = inputs;
        this.runs = runs;
        this.runner = runner;
        this.clock = clock;
        this.telemetry = telemetry;
    }

    @Override
    public CompensationOutcome compensate(CompensationTask task) {
        ActivityExecutionContext activity = Activity.getExecutionContext();
        ActivityInfo info = activity.getInfo();
        NodeTask fwd = task.forward();
        String type = task.compensation() == null ? fwd.nodeType() : task.compensation().type();
        telemetry.attemptStarted(info, fwd.tenantId(), fwd.workflowId(), type);
        try {
            CompensationOutcome outcome = decideAndRun(task, info.getAttempt(), activity);
            if (outcome.result() != Result.SKIPPED) {
                telemetry.compensation(outcome.result() == Result.COMPENSATED);
            }
            return outcome;
        } catch (RuntimeException e) {
            if (HeartbeatingRunner.isCancellation(e)) {
                throw e;
            }
            ApplicationFailure failure = Failures.toApplicationFailure(e);
            if (ActivityTelemetry.isFinalAttempt(info, failure)) {
                telemetry.compensation(false);
            }
            throw failure;
        }
    }

    private CompensationOutcome decideAndRun(CompensationTask task, int attempt, ActivityExecutionContext activity) {
        NodeTask fwd = task.forward();
        for (int round = 0; round <= MAX_RECONCILE_ROUNDS; round++) {
            CompensationDecision decision = reconciler.decide(fwd.tenantId(), fwd.executionId(), fwd.nodeId(),
                    fwd.callIndex(), reversibility(task));
            if (decision.kind() == Kind.COMPENSATE) {
                runInverse(task, attempt, decision.forwardResponse(), activity);
                return new CompensationOutcome(Result.COMPENSATED, decision.reason());
            }
            if (decision.kind() != Kind.RECONCILE_FORWARD) {
                return new CompensationOutcome(resultOf(decision.kind()), decision.reason());
            }
            if (round < MAX_RECONCILE_ROUNDS) {
                reconcileForward(fwd, attempt, activity);
            }
        }
        return new CompensationOutcome(Result.NEEDS_ATTENTION,
                "forward outcome still unknown after " + MAX_RECONCILE_ROUNDS + " reconcile rounds");
    }

    private static Result resultOf(Kind kind) {
        return switch (kind) {
            case COMPENSATE -> Result.COMPENSATED;
            case SKIP, RECONCILE_FORWARD -> Result.SKIPPED;
            case NEEDS_ATTENTION -> Result.NEEDS_ATTENTION;
            case PIVOT_EXECUTED -> Result.PIVOT_EXECUTED;
        };
    }

    private static Reversibility reversibility(CompensationTask task) {
        if (task.pivot()) {
            return Reversibility.PIVOT;
        }
        return task.compensation() != null ? Reversibility.COMPENSATABLE : Reversibility.RETRIABLE;
    }

    /** Re-runs the forward with the same effect key: a NATIVE_KEY provider returns the original outcome. */
    private void reconcileForward(NodeTask fwd, int attempt, ActivityExecutionContext activity) {
        NodeContext ctx = NodeActivityImpl.context(fwd, attempt, inputs.assemble(fwd));
        runner.run(activity, true, () -> executors.resolve(ctx).execute(ctx));
    }

    private void runInverse(CompensationTask task, int attempt, JsonNode forwardResponse,
                            ActivityExecutionContext activity) {
        NodeTask fwd = task.forward();
        RunKey key = new RunKey(fwd.tenantId(), fwd.executionId(), fwd.nodeId(), fwd.callIndex(), Phase.COMPENSATE,
                attempt);
        runs.started(key, clock.instant());
        String type = task.compensation().type();
        try {
            NodeContext ctx = inverseContext(fwd, task.compensation(), attempt, forwardResponse);
            runner.run(activity, true, () -> executors.resolve(ctx).execute(ctx));
            runs.finished(key, NodeStatus.SUCCEEDED.name(), null, null, clock.instant());
            telemetry.nodeCompleted(activity.getInfo(), fwd.tenantId(), fwd.workflowId(), type,
                    NodeStatus.SUCCEEDED.name());
        } catch (RuntimeException e) {
            recordFailure(key, e);
            if (ActivityTelemetry.isFinalAttempt(activity.getInfo(), Failures.toApplicationFailure(e))) {
                telemetry.nodeCompleted(activity.getInfo(), fwd.tenantId(), fwd.workflowId(), type,
                        NodeStatus.FAILED.name());
            }
            throw e;
        }
    }

    private NodeContext inverseContext(NodeTask fwd, Compensation compensation, int attempt, JsonNode response) {
        ObjectNode input = ((ObjectNode) inputs.assemble(fwd)).deepCopy();
        input.set("forward", response == null ? NullNode.getInstance() : response);
        return new NodeContext(fwd.tenantId(), fwd.executionId(), fwd.workflowId(), fwd.defVersion(), fwd.nodeId(),
                compensation.type(), fwd.callIndex(), attempt, Phase.COMPENSATE, fwd.mode(), true,
                compensation.config(), input, Duration.ofSeconds(fwd.timeoutS()), fwd.priority(), fwd.dryRun());
    }

    private void recordFailure(RunKey key, RuntimeException e) {
        boolean cancelled = HeartbeatingRunner.isCancellation(e);
        try {
            runs.finished(key, cancelled ? NodeStatus.CANCELLED.name() : NodeStatus.FAILED.name(),
                    cancelled ? ErrorCodes.CANCELLED : ErrorCodeOf.code(e), e.getMessage(), clock.instant());
        } catch (RuntimeException dbError) {
            log.warn("could not record the compensation failure of {} of {}", key.nodeId(), key.executionId(),
                    dbError);
        }
    }
}
