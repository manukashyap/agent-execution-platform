package com.conversive.aep.support;

import com.conversive.aep.engine.temporal.TemporalProperties;
import com.conversive.aep.engine.workflow.StubDagInterpreterWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * In-process Temporal ({@link TestWorkflowEnvironment}) whose client replaces the app's, with the stub
 * interpreter polling the app task queue.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InProcessTemporal {

    @Bean(destroyMethod = "close")
    TestWorkflowEnvironment testWorkflowEnvironment(TemporalProperties properties) {
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
        env.newWorker(properties.taskQueue()).registerWorkflowImplementationTypes(StubDagInterpreterWorkflowImpl.class);
        env.start();
        return env;
    }

    @Bean
    @Primary
    WorkflowClient testWorkflowClient(TestWorkflowEnvironment env) {
        return env.getWorkflowClient();
    }
}
