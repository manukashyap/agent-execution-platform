package com.conversive.aep.router;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One routed LLM call (one activity attempt; fallbacks happen inside it).
 *
 * @param turn         LLM turn within the node (0 without a tool round; P4 increments it per tool round trip)
 * @param capabilities every one must be offered by the chosen provider (e.g. {@code chat}, {@code tools}, {@code json})
 * @param modelHint    preferred model; used only when it equals a provider's model, otherwise the provider default
 * @param timeout      budget for the whole route, shared by the primary call and its fallbacks
 */
public record LlmRequest(
        TenantId tenantId,
        ExecutionId executionId,
        String nodeId,
        int callIndex,
        int attempt,
        int turn,
        Priority priority,
        Set<String> capabilities,
        List<LlmMessage> messages,
        List<LlmToolSpec> tools,
        String modelHint,
        Duration timeout,
        ExecutionMode mode) {

    public LlmRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(timeout, "timeout");
        priority = priority == null ? Priority.NORMAL : priority;
        capabilities = capabilities == null || capabilities.isEmpty() ? Set.of("chat") : Set.copyOf(capabilities);
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = tools == null ? List.of() : List.copyOf(tools);
        mode = mode == null ? ExecutionMode.LIVE : mode;
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
    }
}
