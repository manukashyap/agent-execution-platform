package com.conversive.aep.tools;

import com.conversive.aep.common.TenantId;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** One row of {@code tenant_tool_grant}: the tenant may call the tool with these scopes. */
public record ToolGrant(TenantId tenantId, String toolName, List<String> scopes) {

    public ToolGrant {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(toolName, "toolName");
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public boolean covers(Collection<String> required) {
        return scopes.containsAll(required);
    }
}
