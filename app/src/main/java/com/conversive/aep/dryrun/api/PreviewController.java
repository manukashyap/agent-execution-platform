package com.conversive.aep.dryrun.api;

import com.conversive.aep.api.ApiEnvelope;
import com.conversive.aep.api.auth.RequiresScope;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantContext;
import com.conversive.aep.dryrun.DryRunPreview;
import com.conversive.aep.dryrun.PreviewService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** T6.2: what a dry run did (outputs, mocked calls) and would undo (the rendered compensation plan). */
@RestController
public class PreviewController {

    private final PreviewService previews;

    public PreviewController(PreviewService previews) {
        this.previews = previews;
    }

    @GetMapping("/v1/executions/{executionId}/preview")
    @RequiresScope(RequiresScope.EXECUTIONS_READ)
    public ApiEnvelope<DryRunPreview> preview(@PathVariable UUID executionId) {
        return ApiEnvelope.ok(previews.preview(TenantContext.require(), new ExecutionId(executionId)));
    }
}
