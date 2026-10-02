package com.conversive.aep.tools;

import com.conversive.aep.common.TenantId;
import java.util.List;
import java.util.Optional;

/** Per-tenant tool grants ({@code tenant_tool_grant}, V4). */
public interface ToolGrants {

    Optional<ToolGrant> find(TenantId tenantId, String toolName);

    List<ToolGrant> findAll(TenantId tenantId);
}
