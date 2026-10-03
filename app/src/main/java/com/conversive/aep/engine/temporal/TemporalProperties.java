package com.conversive.aep.engine.temporal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param target      gRPC frontend, {@code host:port}
 * @param startWorker false in tests that only need a client (stubs connect lazily)
 */
@ConfigurationProperties("aep.temporal")
public record TemporalProperties(String target, String namespace, String taskQueue, boolean startWorker) {

    public TemporalProperties {
        target = target == null ? "localhost:7233" : target;
        namespace = namespace == null ? "default" : namespace;
        taskQueue = taskQueue == null ? "aep-main" : taskQueue;
    }
}
