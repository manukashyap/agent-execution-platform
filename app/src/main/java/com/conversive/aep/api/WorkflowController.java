package com.conversive.aep.api;

import com.conversive.aep.api.auth.RequiresScope;
import com.conversive.aep.api.dto.DefinitionView;
import com.conversive.aep.api.dto.ExecutionView;
import com.conversive.aep.api.dto.StartExecutionBody;
import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.TenantContext;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.definition.DefinitionService.PublishResult;
import com.conversive.aep.execution.service.ExecutionService;
import com.conversive.aep.execution.service.ExecutionService.StartResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/workflows")
public class WorkflowController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final DefinitionService definitions;
    private final ExecutionService executions;

    public WorkflowController(DefinitionService definitions, ExecutionService executions) {
        this.definitions = definitions;
        this.executions = executions;
    }

    /** Accepts the PDF §4 JSON verbatim. 201 when new, 200 for an identical re-publish. */
    @PostMapping
    @RequiresScope(RequiresScope.WORKFLOWS_WRITE)
    public ResponseEntity<ApiEnvelope<DefinitionView>> publish(@RequestBody JsonNode spec) {
        PublishResult result = definitions.publish(TenantContext.require(), spec);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiEnvelope.ok(DefinitionView.summary(result.definition()),
                        Map.of("warnings", result.warnings())));
    }

    @GetMapping("/{workflowId}/versions/{version}")
    @RequiresScope(RequiresScope.WORKFLOWS_READ)
    public ApiEnvelope<DefinitionView> get(@PathVariable String workflowId, @PathVariable int version) {
        return ApiEnvelope.ok(DefinitionView.full(definitions.get(TenantContext.require(), workflowId, version)));
    }

    /** 202 with the execution id; a repeated Idempotency-Key returns the original execution. */
    @PostMapping("/{workflowId}/executions")
    @RequiresScope(RequiresScope.EXECUTIONS_WRITE)
    public ResponseEntity<ApiEnvelope<ExecutionView>> start(
            @PathVariable String workflowId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) StartExecutionBody body) {
        StartExecutionBody request = body == null ? StartExecutionBody.empty() : body;
        StartResult result = executions.start(TenantContext.require(),
                request.toCommand(workflowId, idempotencyKey(idempotencyKey)));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiEnvelope.ok(ExecutionView.of(result.execution()), Map.of("replayed", !result.created())));
    }

    private static String idempotencyKey(String header) {
        if (header == null || header.isBlank()) {
            return "srv-" + UUID.randomUUID();
        }
        if (header.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED,
                    IDEMPOTENCY_KEY + " must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return header.trim();
    }
}
