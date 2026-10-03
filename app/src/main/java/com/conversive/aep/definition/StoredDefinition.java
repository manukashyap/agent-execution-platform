package com.conversive.aep.definition;

import com.conversive.aep.common.TenantId;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** One immutable published version: the spec as submitted plus its canonical hash. */
public record StoredDefinition(
        TenantId tenantId, String workflowId, int version, JsonNode spec, String sha256, Instant createdAt) {
}
