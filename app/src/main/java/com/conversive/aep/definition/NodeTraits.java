package com.conversive.aep.definition;

import com.conversive.aep.common.Reversibility;
import com.conversive.aep.definition.model.NodeSpec;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Effective saga classification of a node: declared flags OR'ed with the classification of the
 * MCP tool it calls. Shared by the validator and the freezer so they can never disagree.
 *
 * @param tool          the catalog entry of an {@code mcp} node's tool; null otherwise or when unknown
 * @param compensatable has a declared {@code compensate} or calls a {@code COMPENSATABLE} tool
 */
public record NodeTraits(ToolInfo tool, boolean sideEffecting, boolean pivot, boolean compensatable,
                         boolean retriable) {

    public static NodeTraits of(NodeSpec node, ToolCatalog catalog) {
        ToolInfo tool = "mcp".equals(node.type()) ? catalog.find(toolName(node.config())).orElse(null) : null;
        Reversibility reversibility = tool == null ? null : tool.reversibility();
        boolean sideEffecting = Boolean.TRUE.equals(node.sideEffecting())
                || (reversibility != null && reversibility != Reversibility.READ_ONLY);
        boolean pivot = Boolean.TRUE.equals(node.pivot()) || reversibility == Reversibility.PIVOT;
        boolean compensatable = node.compensate() != null || reversibility == Reversibility.COMPENSATABLE;
        return new NodeTraits(tool, sideEffecting, pivot, compensatable, reversibility == Reversibility.RETRIABLE);
    }

    /** {@code config.tool} when it is a string, else null. */
    public static String toolName(JsonNode config) {
        if (config == null) {
            return null;
        }
        JsonNode tool = config.path("tool");
        return tool.isTextual() ? tool.asText() : null;
    }
}
