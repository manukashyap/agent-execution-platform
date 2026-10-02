package com.conversive.aep.engine.temporal;

import com.conversive.aep.engine.workflow.StubDagInterpreterWorkflowImpl;
import io.temporal.worker.Worker;
import org.springframework.stereotype.Component;

/**
 * Registers workflow implementations on the main worker before it starts polling
 * ({@link TemporalWorkerLifecycle} starts it on ApplicationReadyEvent). P2a swaps the stub for the
 * real interpreter here.
 */
@Component
public class WorkflowRegistrar {

    public WorkflowRegistrar(Worker mainWorker) {
        mainWorker.registerWorkflowImplementationTypes(StubDagInterpreterWorkflowImpl.class);
    }
}
