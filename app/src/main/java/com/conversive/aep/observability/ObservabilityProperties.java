package com.conversive.aep.observability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Observability settings ({@code aep.observability.*}).
 *
 * @param queueDepth the {@code queue_depth} gauge refresher
 */
@ConfigurationProperties("aep.observability")
public record ObservabilityProperties(QueueDepth queueDepth) {

    private static final Duration DEFAULT_REFRESH = Duration.ofSeconds(10);
    private static final Duration DEFAULT_DESCRIBE_TIMEOUT = Duration.ofSeconds(2);

    public ObservabilityProperties {
        queueDepth = queueDepth == null ? new QueueDepth(true, null, null) : queueDepth;
    }

    /**
     * @param enabled         false stops the refresher (the gauges then stay at 0)
     * @param refresh         period between refreshes
     * @param describeTimeout gRPC deadline of each {@code DescribeTaskQueue} call
     */
    public record QueueDepth(boolean enabled, Duration refresh, Duration describeTimeout) {

        public QueueDepth {
            refresh = refresh == null || refresh.isNegative() || refresh.isZero() ? DEFAULT_REFRESH : refresh;
            describeTimeout = describeTimeout == null || describeTimeout.isNegative() || describeTimeout.isZero()
                    ? DEFAULT_DESCRIBE_TIMEOUT : describeTimeout;
        }
    }
}
