package com.conversive.aep.engine.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Runs one node call: records {@code node_run}, resolves the executor, writes {@code node_output}.
 * Failures surface as {@code ApplicationFailure} whose type is the platform error code.
 */
@ActivityInterface
public interface NodeActivity {

    @ActivityMethod(name = "RunNode")
    NodeOutputRef run(NodeTask task);
}
