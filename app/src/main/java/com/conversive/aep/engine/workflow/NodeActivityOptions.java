package com.conversive.aep.engine.workflow;

import com.conversive.aep.definition.model.FrozenDefinition.FrozenNode;
import com.conversive.aep.definition.model.FrozenDefinition.RetryPolicy;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.common.RetryOptions;
import java.time.Duration;

/** Temporal options derived from a frozen node (06 §2: timeouts, retries, cancellation per node). */
final class NodeActivityOptions {

    /** Several heartbeat intervals ({@code HeartbeatingRunner.INTERVAL}); detects a dead worker. */
    static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration MIN_INTERVAL = Duration.ofMillis(1);
    private static final Duration MAX_INTERVAL = Duration.ofSeconds(60);

    private NodeActivityOptions() {
    }

    static ActivityOptions forNode(FrozenNode node) {
        Duration startToClose = Duration.ofSeconds(node.timeoutS());
        Duration scheduleToClose = Duration.ofSeconds(Math.max(node.scheduleToCloseS(), node.timeoutS()));
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(startToClose)
                .setScheduleToCloseTimeout(scheduleToClose)
                .setHeartbeatTimeout(HEARTBEAT_TIMEOUT)
                .setRetryOptions(retry(node.retry()))
                .setCancellationType(node.sideEffecting()
                        ? ActivityCancellationType.WAIT_CANCELLATION_COMPLETED
                        : ActivityCancellationType.TRY_CANCEL)
                .build();
    }

    static RetryOptions retry(RetryPolicy policy) {
        if (policy == null) {
            return RetryOptions.newBuilder().setMaximumAttempts(1).build();
        }
        Duration initial = Duration.ofMillis(policy.initialIntervalMs());
        return RetryOptions.newBuilder()
                .setMaximumAttempts(Math.max(1, policy.maxAttempts()))
                .setInitialInterval(initial.compareTo(MIN_INTERVAL) < 0 ? MIN_INTERVAL : initial)
                .setBackoffCoefficient(policy.backoff() == null ? 2.0 : Math.max(1.0, policy.backoff().coefficient()))
                .setMaximumInterval(MAX_INTERVAL)
                .build();
    }

    /** Engine state writes: short, retried quickly, bounded. */
    static LocalActivityOptions state() {
        return LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setScheduleToCloseTimeout(Duration.ofMinutes(2))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofMillis(200))
                        .setBackoffCoefficient(2.0)
                        .setMaximumInterval(Duration.ofSeconds(10))
                        .build())
                .build();
    }
}
