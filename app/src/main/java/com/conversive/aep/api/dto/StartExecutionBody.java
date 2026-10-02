package com.conversive.aep.api.dto;

import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Priority;
import com.conversive.aep.execution.service.StartCommand;
import com.conversive.aep.nodes.DryRunOptions;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code POST /v1/workflows/{id}/executions} body (06 §4.2): {@code mode}, {@code input},
 * {@code version?}, {@code dryRun{mockLlm?, allowReadOnly?}}, {@code priority?}. snake_case aliases are
 * accepted to match the definition format.
 */
public record StartExecutionBody(
        ExecutionMode mode,
        JsonNode input,
        Integer version,
        @JsonAlias("dry_run") DryRun dryRun,
        Priority priority) {

    /** Absent flags take {@link DryRunOptions#defaults()}. */
    public record DryRun(@JsonAlias("mock_llm") Boolean mockLlm, @JsonAlias("allow_read_only") Boolean allowReadOnly) {

        DryRunOptions toOptions() {
            DryRunOptions d = DryRunOptions.defaults();
            return new DryRunOptions(mockLlm == null ? d.mockLlm() : mockLlm,
                    allowReadOnly == null ? d.allowReadOnly() : allowReadOnly);
        }
    }

    public static StartExecutionBody empty() {
        return new StartExecutionBody(null, null, null, null, null);
    }

    public StartCommand toCommand(String workflowId, String idempotencyKey) {
        return new StartCommand(workflowId, version, input, mode, dryRun == null ? null : dryRun.toOptions(),
                priority, idempotencyKey);
    }
}
