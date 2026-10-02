package com.conversive.aep.tools;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.TenantId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.Objects;

/**
 * One gateway call. {@code (tenantId, executionId, nodeId, phase, callIndex)} is the side-effect identity
 * ({@code EffectKey}); {@code attempt} is the Temporal activity attempt (1-based).
 *
 * @param startToClose the node's activity StartToClose; null means the tool's registry timeout
 */
public record ToolInvocation(
        TenantId tenantId,
        ExecutionId executionId,
        String nodeId,
        int callIndex,
        int attempt,
        Phase phase,
        ExecutionMode mode,
        String toolName,
        JsonNode args,
        Duration startToClose) {

    public ToolInvocation {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(toolName, "toolName");
        phase = phase == null ? Phase.FORWARD : phase;
        mode = mode == null ? ExecutionMode.LIVE : mode;
        args = args == null || args.isNull() || args.isMissingNode() ? JsonNodeFactory.instance.objectNode() : args;
        if (callIndex < 0 || attempt < 1) {
            throw new IllegalArgumentException("callIndex must be >= 0 and attempt >= 1");
        }
    }

    public Duration startToCloseOr(Duration fallback) {
        return startToClose == null ? fallback : startToClose;
    }
}
