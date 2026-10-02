package com.conversive.aep.api;

import com.conversive.aep.api.auth.RequiresScope;
import com.conversive.aep.api.dto.ExecutionView;
import com.conversive.aep.api.dto.NodeRunView;
import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.TenantContext;
import com.conversive.aep.execution.service.ExecutionService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/executions/{executionId}")
public class ExecutionController {

    private final ExecutionService executions;

    public ExecutionController(ExecutionService executions) {
        this.executions = executions;
    }

    @GetMapping
    @RequiresScope(RequiresScope.EXECUTIONS_READ)
    public ApiEnvelope<ExecutionView> get(@PathVariable UUID executionId) {
        return ApiEnvelope.ok(ExecutionView.of(executions.get(TenantContext.require(), new ExecutionId(executionId))));
    }

    @GetMapping("/nodes")
    @RequiresScope(RequiresScope.EXECUTIONS_READ)
    public ApiEnvelope<List<NodeRunView>> nodes(@PathVariable UUID executionId) {
        return ApiEnvelope.ok(executions.nodes(TenantContext.require(), new ExecutionId(executionId)).stream()
                .map(NodeRunView::of).toList());
    }

    /** Cancellation is asynchronous: 202 with the current state; the workflow records CANCELLED. */
    @DeleteMapping
    @RequiresScope(RequiresScope.EXECUTIONS_WRITE)
    public ResponseEntity<ApiEnvelope<ExecutionView>> cancel(@PathVariable UUID executionId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiEnvelope.ok(
                ExecutionView.of(executions.cancel(TenantContext.require(), new ExecutionId(executionId)))));
    }

    @GetMapping("/preview")
    @RequiresScope(RequiresScope.EXECUTIONS_READ)
    public ApiEnvelope<Void> preview(@PathVariable UUID executionId) {
        throw notImplemented("preview");
    }

    private static NonRetryableError notImplemented(String what) {
        return new NonRetryableError(ErrorCodes.NOT_IMPLEMENTED, what + " is not available yet");
    }
}
