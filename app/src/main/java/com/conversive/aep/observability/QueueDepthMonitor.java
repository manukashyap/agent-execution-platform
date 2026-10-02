package com.conversive.aep.observability;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Refreshes the {@code queue_depth} gauges on a fixed period: Temporal backlog per task type and executions
 * still QUEUED. A failing source keeps its last value; the first failure per source is a WARN, repeats DEBUG.
 */
public class QueueDepthMonitor implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(QueueDepthMonitor.class);

    private final TaskQueueBacklog backlog;
    private final LongSupplier queuedExecutions;
    private final AepMetrics metrics;
    private final ObservabilityProperties.QueueDepth props;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile ScheduledExecutorService scheduler;

    public QueueDepthMonitor(TaskQueueBacklog backlog, LongSupplier queuedExecutions, AepMetrics metrics,
                             ObservabilityProperties.QueueDepth props) {
        this.backlog = backlog;
        this.queuedExecutions = queuedExecutions;
        this.metrics = metrics;
        this.props = props;
    }

    /** One refresh of every source; never throws. */
    public void refresh() {
        update(AepMetrics.SOURCE_TEMPORAL_WORKFLOW, backlog::workflowTasks);
        update(AepMetrics.SOURCE_TEMPORAL_ACTIVITY, backlog::activityTasks);
        update(AepMetrics.SOURCE_ADMISSION_QUEUED, queuedExecutions);
    }

    @Override
    public synchronized void start() {
        if (!props.enabled() || scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aep-queue-depth");
            t.setDaemon(true);
            return t;
        });
        long periodMs = props.refresh().toMillis();
        scheduler.scheduleWithFixedDelay(this::refresh, 0, periodMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }

    private void update(String source, LongSupplier reading) {
        try {
            metrics.queueDepth(source, reading.getAsLong());
            warned.remove(source);
        } catch (RuntimeException e) {
            if (warned.add(source)) {
                LOG.warn("queue_depth refresh failed for source={}; keeping the last value", source, e);
            } else {
                LOG.debug("queue_depth refresh still failing for source={}", source, e);
            }
        }
    }
}
