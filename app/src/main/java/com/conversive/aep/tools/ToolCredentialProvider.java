package com.conversive.aep.tools;

import com.conversive.aep.common.TenantId;
import java.util.Optional;

/**
 * Per-tenant tool credentials (06 §4.8 AuthN/Z boundary). The gateway injects the value as the outbound
 * {@code Authorization} header only; it never reaches tool args, node output, audit rows, logs or LLM prompts.
 */
public interface ToolCredentialProvider {

    Optional<String> credential(TenantId tenantId, String toolName);
}
