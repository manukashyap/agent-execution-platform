package com.conversive.aep.engine.activity;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.observability.AepMetrics;
import io.temporal.activity.ActivityInfo;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Engine call sites on {@link AepMetrics}: attempt entry, a node's final outcome, compensation steps. */
@Component
public class ActivityTelemetry {

    private final AepMetrics metrics;
    private final Clock clock;

    public ActivityTelemetry(AepMetrics metrics, Clock clock) {
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Schedule-to-start of this attempt, and a retry count when it is not the first. */
    void attemptStarted(ActivityInfo info, TenantId tenant, String workflowId, String nodeType) {
        metrics.scheduleToStart(tenant, since(info.getCurrentAttemptScheduledTimestamp()));
        if (info.getAttempt() > 1) {
            metrics.nodeRetried(tenant, workflowId, nodeType);
        }
    }

    /** A node call reached its final status; latency spans every attempt since the first was scheduled. */
    void nodeCompleted(ActivityInfo info, TenantId tenant, String workflowId, String nodeType, String status) {
        metrics.nodeCompleted(tenant, workflowId, nodeType, status, since(info.getScheduledTimestamp()));
    }

    void compensation(boolean succeeded) {
        metrics.compensation(succeeded ? AepMetrics.OUTCOME_SUCCEEDED : AepMetrics.OUTCOME_FAILED);
    }

    void executionCompleted(TenantId tenant, String workflowId, String status, Instant startedAt) {
        Duration latency = startedAt == null ? Duration.ZERO : Duration.between(startedAt, clock.instant());
        metrics.executionCompleted(tenant, workflowId, status, latency.isNegative() ? Duration.ZERO : latency);
    }

    /** True when Temporal will not schedule another attempt after {@code failure}. */
    static boolean isFinalAttempt(ActivityInfo info, RuntimeException failure) {
        if (failure instanceof ApplicationFailure af && af.isNonRetryable()) {
            return true;
        }
        RetryOptions retry = info.getRetryOptions();
        return retry != null && retry.getMaximumAttempts() > 0 && info.getAttempt() >= retry.getMaximumAttempts();
    }

    private Duration since(long epochMillis) {
        if (epochMillis <= 0) {
            return Duration.ZERO;
        }
        Duration d = Duration.between(Instant.ofEpochMilli(epochMillis), clock.instant());
        return d.isNegative() ? Duration.ZERO : d;
    }
}
