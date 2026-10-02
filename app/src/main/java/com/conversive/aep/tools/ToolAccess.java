package com.conversive.aep.tools;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.TenantId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Registry lookup plus the tenant's grant: a tool is usable only if granted with every scope it requires. */
@Component
public class ToolAccess {

    private final ToolRegistry registry;
    private final ToolGrants grants;

    public ToolAccess(ToolRegistry registry, ToolGrants grants) {
        this.registry = registry;
        this.grants = grants;
    }

    public Optional<ToolDefinition> find(String toolName) {
        return registry.find(toolName);
    }

    /** @throws NonRetryableError {@code TOOL_NOT_FOUND} or {@code TOOL_FORBIDDEN} */
    public ToolDefinition authorize(TenantId tenantId, String toolName) {
        ToolDefinition tool = registry.find(toolName)
                .orElseThrow(() -> new NonRetryableError(ErrorCodes.TOOL_NOT_FOUND, "unknown tool: " + toolName));
        authorize(tenantId, tool);
        return tool;
    }

    public void authorize(TenantId tenantId, ToolDefinition tool) {
        boolean allowed = grants.find(tenantId, tool.name()).filter(g -> g.covers(tool.scopes())).isPresent();
        if (!allowed) {
            throw new NonRetryableError(ErrorCodes.TOOL_FORBIDDEN,
                    "tenant " + tenantId + " is not granted tool " + tool.name() + " with scopes " + tool.scopes());
        }
    }

    public List<ToolDefinition> granted(TenantId tenantId) {
        return grants.findAll(tenantId).stream()
                .flatMap(g -> registry.find(g.toolName()).filter(t -> g.covers(t.scopes())).stream())
                .toList();
    }
}
