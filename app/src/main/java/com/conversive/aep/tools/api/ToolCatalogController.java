package com.conversive.aep.tools.api;

import com.conversive.aep.api.auth.RequiresScope;
import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.TenantContext;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.tools.ToolAccess;
import com.conversive.aep.tools.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/tools}: the calling tenant's granted tools. The tenant comes from {@link TenantContext}, which the
 * P1 API-key filter populates; this endpoint must sit behind that filter (without it every call is 401 here).
 */
@RestController
public class ToolCatalogController {

    private final ToolAccess access;

    public ToolCatalogController(ToolAccess access) {
        this.access = access;
    }

    public record ToolView(String name, String description, JsonNode inputSchema, String reversibility,
                           String idempotency) {

        static ToolView of(ToolDefinition tool) {
            return new ToolView(tool.name(), tool.description(), tool.inputSchema(), tool.reversibility().name(),
                    tool.idempotency().name());
        }
    }

    public record ApiError(String code, String message) {
    }

    public record Envelope<T>(T data, ApiError error, Map<String, Object> meta) {
    }

    @GetMapping("/v1/tools")
    @RequiresScope(RequiresScope.WORKFLOWS_READ)
    public ResponseEntity<Envelope<List<ToolView>>> list() {
        Optional<TenantId> tenant = TenantContext.current();
        if (tenant.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new Envelope<>(null,
                    new ApiError(ErrorCodes.UNAUTHORIZED, "missing or invalid API key"), Map.of()));
        }
        List<ToolView> tools = access.granted(tenant.get()).stream()
                .sorted(Comparator.comparing(ToolDefinition::name))
                .map(ToolView::of)
                .toList();
        return ResponseEntity.ok(new Envelope<>(tools, null, Map.of("count", tools.size())));
    }
}
