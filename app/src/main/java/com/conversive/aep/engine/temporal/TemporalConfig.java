package com.conversive.aep.engine.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal wired by hand (not the Spring Boot starter) so the connection settings stay explicit. */
@Configuration(proxyBeanMethods = false)
public class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs workflowServiceStubs(TemporalProperties properties) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(properties.target()).build());
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs, TemporalProperties properties) {
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(properties.namespace()).build());
    }

    @Bean(destroyMethod = "shutdown")
    WorkerFactory workerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    /** Later phases register workflow and activity implementations on this worker. */
    @Bean
    Worker mainWorker(WorkerFactory factory, TemporalProperties properties) {
        return factory.newWorker(properties.taskQueue());
    }

    @Bean
    TemporalWorkerLifecycle temporalWorkerLifecycle(WorkerFactory factory, TemporalProperties properties) {
        return new TemporalWorkerLifecycle(factory, properties.startWorker());
    }
}
