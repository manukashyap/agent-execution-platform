package com.conversive.aep.engine.activity;

import com.conversive.aep.engine.persistence.NodeRunRepository;
import com.conversive.aep.execution.persistence.ExecutionRecord;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.persistence.StatusUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** {@link ExecutionStateActivity} over {@link ExecutionRepository#cas} and {@code node_run}. */
@Component
public class ExecutionStateActivityImpl implements ExecutionStateActivity {

    private final ExecutionRepository executions;
    private final NodeRunRepository runs;
    private final NodeInputAssembler inputs;
    private final Clock clock;

    public ExecutionStateActivityImpl(ExecutionRepository executions, NodeRunRepository runs,
                                      NodeInputAssembler inputs, Clock clock) {
        this.executions = executions;
        this.runs = runs;
        this.inputs = inputs;
        this.clock = clock;
    }

    @Override
    public TransitionResult transition(Transition t) {
        StatusUpdate update = new StatusUpdate(clock.instant(), t.errorCode(), t.errorMessage(), t.output());
        boolean applied = executions.cas(t.tenantId(), t.executionId(), t.from(), t.to(), update);
        if (applied) {
            return new TransitionResult(true, t.to());
        }
        return new TransitionResult(false, executions.findById(t.tenantId(), t.executionId())
                .map(ExecutionRecord::status).orElse(null));
    }

    @Override
    public void markNodes(NodeMarks marks) {
        Instant now = clock.instant();
        for (String nodeId : marks.nodeIds()) {
            runs.settled(marks.tenantId(), marks.executionId(), nodeId, marks.status().name(), marks.errorCode(),
                    marks.errorMessage(), now);
        }
    }

    @Override
    public JsonNode loadOutput(OutputQuery query) {
        return inputs.output(query.tenantId(), query.executionId(), query.nodeId(), query.fanOut());
    }
}
