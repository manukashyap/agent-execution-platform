package com.conversive.aep.support;

import com.conversive.aep.engine.activity.ExecutionStateActivity;
import com.conversive.aep.engine.activity.NodeActivity;
import com.conversive.aep.engine.temporal.TemporalProperties;
import com.conversive.aep.engine.temporal.WorkflowRegistrar;
import com.conversive.aep.nodes.ExecutorRegistry;
import com.conversive.aep.nodes.NodeExecutor;
import com.conversive.aep.nodes.TypeExecutorRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * In-process Temporal ({@link TestWorkflowEnvironment}) whose client replaces the app's, with the real
 * interpreter and engine activities polling the app task queue. Node types not listed in
 * {@code test.real-node-types} (default: none) run on the {@link ScriptedExecutor}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InProcessTemporal {

    @Bean(destroyMethod = "close")
    TestWorkflowEnvironment testWorkflowEnvironment(TemporalProperties properties, NodeActivity nodeActivity,
                                                    ExecutionStateActivity stateActivity) {
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(
                TestEnvironmentOptions.newBuilder().setUseTimeskipping(false).build());
        WorkflowRegistrar.register(env.newWorker(properties.taskQueue()), List.of(nodeActivity, stateActivity));
        env.start();
        return env;
    }

    @Bean
    @Primary
    WorkflowClient testWorkflowClient(TestWorkflowEnvironment env) {
        return env.getWorkflowClient();
    }

    @Bean
    ScriptedExecutor scriptedExecutor() {
        return new ScriptedExecutor();
    }

    @Bean
    @Primary
    ExecutorRegistry scriptedRegistry(List<NodeExecutor> executors, ScriptedExecutor scripted,
                                      @Value("${test.real-node-types:}") String realTypes) {
        TypeExecutorRegistry real = new TypeExecutorRegistry(executors);
        Set<String> realSet = Arrays.stream(realTypes.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return ctx -> realSet.contains(ctx.nodeType()) ? real.resolve(ctx) : scripted;
    }
}
