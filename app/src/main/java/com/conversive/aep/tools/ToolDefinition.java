package com.conversive.aep.tools;

import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.Reversibility;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One row of {@code tool_registry}; the registry is the authority on reversibility and idempotency (05 §2).
 *
 * @param retry         {@code {max_attempts, initial_interval_ms, backoff_coefficient, max_interval_ms}}
 * @param lookup        for {@code LOOKUP} tools: {@code {tool, args}} describing how to find an earlier effect
 * @param dryRunExample canned output used by the dry-run mock executor
 */
public record ToolDefinition(
        String name,
        int version,
        String description,
        JsonNode inputSchema,
        JsonNode outputSchema,
        List<String> scopes,
        Duration timeout,
        JsonNode retry,
        Reversibility reversibility,
        IdempotencyMode idempotency,
        String compensationTool,
        JsonNode lookup,
        JsonNode dryRunExample) {

    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(reversibility, "reversibility");
        Objects.requireNonNull(idempotency, "idempotency");
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public boolean sideEffecting() {
        return reversibility != Reversibility.READ_ONLY;
    }

    public Optional<String> compensation() {
        return Optional.ofNullable(compensationTool);
    }
}
