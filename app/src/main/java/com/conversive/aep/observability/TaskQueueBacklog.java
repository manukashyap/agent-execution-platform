package com.conversive.aep.observability;

/** Approximate backlog of the platform's Temporal task queue, per task type. */
public interface TaskQueueBacklog {

    /** @return pending workflow tasks */
    long workflowTasks();

    /** @return pending activity tasks */
    long activityTasks();
}
