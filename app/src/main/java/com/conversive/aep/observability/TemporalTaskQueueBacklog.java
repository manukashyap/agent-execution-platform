package com.conversive.aep.observability;

import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse;
import io.temporal.client.WorkflowClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * {@code DescribeTaskQueue} with {@code report_stats} (verified in T0.7): the server's approximate backlog
 * count for one task type, aggregated over the queue's partitions.
 */
public class TemporalTaskQueueBacklog implements TaskQueueBacklog {

    private final WorkflowClient client;
    private final String namespace;
    private final String taskQueue;
    private final Duration timeout;

    public TemporalTaskQueueBacklog(WorkflowClient client, String namespace, String taskQueue, Duration timeout) {
        this.client = client;
        this.namespace = namespace;
        this.taskQueue = taskQueue;
        this.timeout = timeout;
    }

    @Override
    public long workflowTasks() {
        return backlog(TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW);
    }

    @Override
    public long activityTasks() {
        return backlog(TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY);
    }

    private long backlog(TaskQueueType type) {
        DescribeTaskQueueRequest request = DescribeTaskQueueRequest.newBuilder()
                .setNamespace(namespace)
                .setTaskQueue(TaskQueue.newBuilder().setName(taskQueue).build())
                .setTaskQueueType(type)
                .setReportStats(true)
                .build();
        DescribeTaskQueueResponse response = client.getWorkflowServiceStubs().blockingStub()
                .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .describeTaskQueue(request);
        return response.hasStats() ? response.getStats().getApproximateBacklogCount() : 0;
    }
}
