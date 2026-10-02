package com.conversive.aep.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.tenancy.TenantTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class QueueDepthMonitorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AepMetrics metrics = new AepMetrics(registry, tenant -> TenantTier.STANDARD);
    private final FakeBacklog backlog = new FakeBacklog();
    private final AtomicLong queued = new AtomicLong();

    private final QueueDepthMonitor monitor = new QueueDepthMonitor(backlog, queued::get, metrics,
            new ObservabilityProperties.QueueDepth(true, Duration.ofSeconds(10), Duration.ofSeconds(1)));

    @Test
    void refreshPublishesEachSource() {
        backlog.workflow = 4;
        backlog.activity = 9;
        queued.set(2);

        monitor.refresh();

        assertThat(depth(AepMetrics.SOURCE_TEMPORAL_WORKFLOW)).isEqualTo(4);
        assertThat(depth(AepMetrics.SOURCE_TEMPORAL_ACTIVITY)).isEqualTo(9);
        assertThat(depth(AepMetrics.SOURCE_ADMISSION_QUEUED)).isEqualTo(2);
    }

    @Test
    void aTemporalFailureKeepsTheLastValueAndStillRefreshesTheDatabaseCount() {
        backlog.activity = 5;
        monitor.refresh();
        backlog.failing = true;
        queued.set(3);

        monitor.refresh();

        assertThat(depth(AepMetrics.SOURCE_TEMPORAL_ACTIVITY)).isEqualTo(5);
        assertThat(depth(AepMetrics.SOURCE_ADMISSION_QUEUED)).isEqualTo(3);
    }

    @Test
    void aDisabledMonitorNeverStarts() {
        QueueDepthMonitor disabled = new QueueDepthMonitor(backlog, queued::get, metrics,
                new ObservabilityProperties.QueueDepth(false, null, null));

        disabled.start();

        assertThat(disabled.isRunning()).isFalse();
    }

    @Test
    void startAndStopManageTheRefresher() {
        monitor.start();
        assertThat(monitor.isRunning()).isTrue();

        monitor.stop();

        assertThat(monitor.isRunning()).isFalse();
    }

    private double depth(String source) {
        return registry.get(AepMetrics.QUEUE_DEPTH).tag(AepMetrics.TAG_SOURCE, source).gauge().value();
    }

    private static final class FakeBacklog implements TaskQueueBacklog {
        long workflow;
        long activity;
        boolean failing;

        @Override
        public long workflowTasks() {
            failIfAsked();
            return workflow;
        }

        @Override
        public long activityTasks() {
            failIfAsked();
            return activity;
        }

        private void failIfAsked() {
            if (failing) {
                throw new IllegalStateException("temporal unavailable");
            }
        }
    }
}
