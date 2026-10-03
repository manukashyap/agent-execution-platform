package com.conversive.aep.definition.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The resolved definition an execution runs: explicit dependencies, defaults applied, tool
 * classification folded in. It is the workflow input, so it stays a plain Jackson record
 * (camelCase JSON) with no Spring types. Nodes keep the submitted list order.
 *
 * @param sha256      canonical hash of the submitted spec (identifies the published version)
 * @param maxParallel max node activities in flight at once (default 16, cap 100)
 */
public record FrozenDefinition(
        String workflowId,
        int version,
        String sha256,
        List<FrozenNode> nodes,
        int maxDurationS,
        int maxParallel,
        ExecutionLimits limits) {

    public FrozenDefinition {
        Objects.requireNonNull(workflowId, "workflowId");
        nodes = List.copyOf(nodes);
        Objects.requireNonNull(limits, "limits");
    }

    public Optional<FrozenNode> node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
    }

    /**
     * @param config         never null (empty object when absent)
     * @param dependsOn      explicit, already resolved from list order where implicit
     * @param forEach        null unless the node fans out
     * @param sideEffecting  declared, or implied by a non-read-only tool
     * @param compensate     null when the node has no compensation
     * @param pivot          declared, or implied by a {@code PIVOT} tool
     */
    public record FrozenNode(
            String id,
            String type,
            JsonNode config,
            List<String> dependsOn,
            ForEach forEach,
            RetryPolicy retry,
            int timeoutS,
            int scheduleToCloseS,
            boolean sideEffecting,
            Compensation compensate,
            boolean pivot,
            OnFailure onFailure) {

        public FrozenNode {
            dependsOn = List.copyOf(dependsOn);
        }
    }

    /** @param items path into upstream output ({@code $.<nodeId>.<field>...}); see {@code ValuePath}. */
    public record ForEach(String items, int maxConcurrency) {
    }

    public record RetryPolicy(int maxAttempts, long initialIntervalMs, Backoff backoff) {
    }

    /** For {@code type = "mcp"} the tool is in {@code config.tool}. */
    public record Compensation(String type, JsonNode config) {
    }

    /** @param maxFanout max items one {@code forEach} may expand to at runtime ({@code FANOUT_LIMIT}). */
    public record ExecutionLimits(BigDecimal maxCostUsd, long maxTokens, int maxNodeExecutions, int maxFanout) {
    }
}
