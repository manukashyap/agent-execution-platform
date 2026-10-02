package com.conversive.aep.observability.api;

import com.conversive.aep.api.ApiEnvelope;
import com.conversive.aep.api.auth.RequiresScope;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantContext;
import com.conversive.aep.observability.trace.ExecutionTrace;
import com.conversive.aep.observability.trace.TraceService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** T8.2: per-execution timeline; the per-tenant view that metrics deliberately do not offer. */
@RestController
public class TraceController {

    private final TraceService traces;

    public TraceController(TraceService traces) {
        this.traces = traces;
    }

    @GetMapping("/v1/executions/{executionId}/trace")
    @RequiresScope(RequiresScope.EXECUTIONS_READ)
    public ApiEnvelope<ExecutionTrace> trace(@PathVariable UUID executionId) {
        return ApiEnvelope.ok(traces.trace(TenantContext.require(), new ExecutionId(executionId)));
    }
}
