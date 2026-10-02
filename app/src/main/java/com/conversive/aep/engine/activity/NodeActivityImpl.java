package com.conversive.aep.engine.activity;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.Failures;
import com.conversive.aep.common.Hashing;
import com.conversive.aep.common.Phase;
import com.conversive.aep.engine.persistence.NodeOutputRepository;
import com.conversive.aep.engine.persistence.NodeRunRepository;
import com.conversive.aep.engine.persistence.NodeRunRepository.RunKey;
import com.conversive.aep.engine.workflow.NodeStatus;
import com.conversive.aep.nodes.ExecutorRegistry;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.nodes.NodeExecutor;
import com.conversive.aep.nodes.NodeResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** {@link NodeActivity}: {@code node_run} bookkeeping around one executor call, then {@code node_output}. */
@Component
public class NodeActivityImpl implements NodeActivity {

    private static final Logger log = LoggerFactory.getLogger(NodeActivityImpl.class);

    private final ExecutorRegistry executors;
    private final NodeInputAssembler inputs;
    private final NodeRunRepository runs;
    private final NodeOutputRepository outputs;
    private final HeartbeatingRunner runner;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ActivityTelemetry telemetry;

    public NodeActivityImpl(ExecutorRegistry executors, NodeInputAssembler inputs, NodeRunRepository runs,
                            NodeOutputRepository outputs, HeartbeatingRunner runner, ObjectMapper mapper,
                            Clock clock, ActivityTelemetry telemetry) {
        this.executors = executors;
        this.inputs = inputs;
        this.runs = runs;
        this.outputs = outputs;
        this.runner = runner;
        this.mapper = mapper;
        this.clock = clock;
        this.telemetry = telemetry;
    }

    @Override
    public NodeOutputRef run(NodeTask task) {
        ActivityExecutionContext activity = Activity.getExecutionContext();
        ActivityInfo info = activity.getInfo();
        int attempt = info.getAttempt();
        telemetry.attemptStarted(info, task.tenantId(), task.workflowId(), task.nodeType());
        RunKey key = new RunKey(task.tenantId(), task.executionId(), task.nodeId(), task.callIndex(), Phase.FORWARD,
                attempt);
        runs.started(key, clock.instant());
        try {
            NodeContext ctx = context(task, attempt, inputs.assemble(task));
            NodeExecutor executor = executors.resolve(ctx);
            NodeResult result = runner.run(activity, task.sideEffecting(), () -> executor.execute(ctx));
            NodeOutputRef ref = store(task, attempt, result);
            runs.finished(key, NodeStatus.SUCCEEDED.name(), null, null, clock.instant());
            telemetry.nodeCompleted(info, task.tenantId(), task.workflowId(), task.nodeType(),
                    NodeStatus.SUCCEEDED.name());
            return ref;
        } catch (RuntimeException e) {
            RuntimeException failure = fail(key, e);
            reportFailure(info, task, failure);
            throw failure;
        }
    }

    static NodeContext context(NodeTask task, int attempt, JsonNode input) {
        return new NodeContext(task.tenantId(), task.executionId(), task.workflowId(), task.defVersion(),
                task.nodeId(), task.nodeType(), task.callIndex(), attempt, Phase.FORWARD, task.mode(),
                task.sideEffecting(), task.config(), input, Duration.ofSeconds(task.timeoutS()), task.priority(),
                task.dryRun());
    }

    private NodeOutputRef store(NodeTask task, int attempt, NodeResult result) {
        JsonNode output = result.output() == null ? NullNode.getInstance() : result.output();
        String json = write(output);
        String sha = Hashing.sha256Hex(json);
        outputs.write(task.tenantId(), task.executionId(), task.nodeId(), task.callIndex(), attempt, json, sha);
        int size = json.getBytes(StandardCharsets.UTF_8).length;
        JsonNode inline = size <= NodeOutputRef.INLINE_LIMIT_BYTES ? output : null;
        return new NodeOutputRef(task.nodeId(), task.callIndex(), attempt, sha, size, inline, result.costUsd(),
                result.tokens());
    }

    private RuntimeException fail(RunKey key, RuntimeException e) {
        boolean cancelled = HeartbeatingRunner.isCancellation(e);
        String code = cancelled ? ErrorCodes.CANCELLED : ErrorCodeOf.code(e);
        try {
            runs.finished(key, cancelled ? NodeStatus.CANCELLED.name() : NodeStatus.FAILED.name(), code,
                    e.getMessage(), clock.instant());
        } catch (RuntimeException dbError) {
            log.warn("could not record the failure of node {} of {}", key.nodeId(), key.executionId(), dbError);
        }
        return cancelled ? e : Failures.toApplicationFailure(e);
    }

    private void reportFailure(ActivityInfo info, NodeTask task, RuntimeException failure) {
        boolean cancelled = HeartbeatingRunner.isCancellation(failure);
        if (cancelled || ActivityTelemetry.isFinalAttempt(info, failure)) {
            telemetry.nodeCompleted(info, task.tenantId(), task.workflowId(), task.nodeType(),
                    cancelled ? NodeStatus.CANCELLED.name() : NodeStatus.FAILED.name());
        }
    }

    private String write(JsonNode output) {
        try {
            return mapper.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("node output is not serialisable", e);
        }
    }
}
