package com.conversive.aep.engine.temporal;

import com.conversive.aep.engine.activity.CompensationActivity;
import com.conversive.aep.engine.activity.ExecutionStateActivity;
import com.conversive.aep.engine.activity.NodeActivity;
import com.conversive.aep.engine.workflow.DagInterpreterWorkflowImpl;
import io.temporal.worker.Worker;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Registers the interpreter and the engine activities on the main worker before it starts polling
 * ({@link TemporalWorkerLifecycle} starts it on ApplicationReadyEvent).
 */
@Component
public class WorkflowRegistrar {

    public WorkflowRegistrar(Worker mainWorker, NodeActivity nodeActivity, ExecutionStateActivity stateActivity,
                             CompensationActivity compensationActivity) {
        register(mainWorker, List.of(nodeActivity, stateActivity, compensationActivity));
    }

    /** Also used by tests to register the same implementations on an in-process worker. */
    public static void register(Worker worker, List<Object> activities) {
        worker.registerWorkflowImplementationTypes(DagInterpreterWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities.toArray());
    }
}
