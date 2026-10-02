package com.conversive.aep.engine.activity;

import com.conversive.aep.definition.model.FrozenDefinition.Compensation;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/**
 * Reconcile-then-compensate for one forward call (06 §4.9): asks the reconciler what happened, then runs the
 * inverse through the guard with {@code Phase.COMPENSATE}. Retryable failures (refund 5xx, a live forward
 * lease) surface as retryable {@code ApplicationFailure}s; the saga bounds them with the activity retry policy.
 */
@ActivityInterface
public interface CompensationActivity {

    @ActivityMethod(name = "CompensateNode")
    CompensationOutcome compensate(CompensationTask task);

    /**
     * @param forward      the forward call as it was scheduled (same node, call index and upstream)
     * @param compensation the inverse; null for a pivot without one
     * @param pivot        the forward node is a pivot (irreversible)
     */
    record CompensationTask(NodeTask forward, Compensation compensation, boolean pivot) {

        public CompensationTask {
            Objects.requireNonNull(forward, "forward");
        }
    }

    /** @param result what happened; {@code reason} says why */
    record CompensationOutcome(Result result, String reason) {
    }

    /** Engine-owned mirror of the reconciler's decision, so workflow code never imports the ledger. */
    enum Result {
        /** The inverse ran. */
        COMPENSATED,
        /** Nothing to undo. */
        SKIPPED,
        /** The forward outcome cannot be determined; a human must check. */
        NEEDS_ATTENTION,
        /** An irreversible effect happened; nothing before it may be rolled back. */
        PIVOT_EXECUTED
    }
}
