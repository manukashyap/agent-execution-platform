package com.conversive.aep.observability;

import com.conversive.aep.engine.temporal.TemporalProperties;
import com.conversive.aep.observability.persistence.QueuedExecutionCounter;
import com.conversive.aep.tenancy.CachedTenantTiers;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.client.WorkflowClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    @Bean
    AepMetrics aepMetrics(MeterRegistry registry, CachedTenantTiers tiers) {
        return new AepMetrics(registry, tiers);
    }

    @Bean
    TaskQueueBacklog taskQueueBacklog(WorkflowClient client, TemporalProperties temporal,
                                      ObservabilityProperties props) {
        return new TemporalTaskQueueBacklog(client, temporal.namespace(), temporal.taskQueue(),
                props.queueDepth().describeTimeout());
    }

    @Bean
    QueueDepthMonitor queueDepthMonitor(TaskQueueBacklog backlog, QueuedExecutionCounter queued, AepMetrics metrics,
                                        ObservabilityProperties props) {
        return new QueueDepthMonitor(backlog, queued::countQueued, metrics, props.queueDepth());
    }
}
