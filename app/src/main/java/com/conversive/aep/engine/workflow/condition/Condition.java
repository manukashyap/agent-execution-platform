package com.conversive.aep.engine.workflow.condition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A parsed condition node config:
 * <pre>{"left": "$.node.path", "op": "eq|ne|gt|gte|lt|lte|exists", "right": &lt;json&gt;,
 *  "then": ["nodeId", ...], "else": ["nodeId", ...]}</pre>
 * When it evaluates true the {@code else} nodes are skipped, otherwise the {@code then} nodes.
 * {@code right} is ignored for {@code exists}.
 */
public record Condition(ValuePath left, Op op, JsonNode right, List<String> thenNodes, List<String> elseNodes) {

    public enum Op {
        EQ, NE, GT, GTE, LT, LTE, EXISTS;

        @JsonCreator
        public static Op fromJson(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public Condition {
        thenNodes = List.copyOf(thenNodes);
        elseNodes = List.copyOf(elseNodes);
    }

    /** @throws ConditionSyntaxException with a message suitable for the API */
    public static Condition parse(JsonNode config) {
        if (config == null || !config.isObject()) {
            throw new ConditionSyntaxException("condition config must be an object");
        }
        JsonNode left = config.path("left");
        if (!left.isTextual()) {
            throw new ConditionSyntaxException("condition 'left' must be a path string");
        }
        Op op = parseOp(config.path("op"));
        JsonNode right = config.path("right");
        if (op != Op.EXISTS && right.isMissingNode()) {
            throw new ConditionSyntaxException("condition op '" + op.name().toLowerCase(Locale.ROOT) + "' needs 'right'");
        }
        return new Condition(ValuePath.parse(left.asText()), op, right.isMissingNode() ? null : right.deepCopy(),
                nodeIds(config, "then"), nodeIds(config, "else"));
    }

    private static Op parseOp(JsonNode op) {
        if (!op.isTextual()) {
            throw new ConditionSyntaxException("condition 'op' must be one of eq, ne, gt, gte, lt, lte, exists");
        }
        try {
            return Op.fromJson(op.asText());
        } catch (IllegalArgumentException e) {
            throw new ConditionSyntaxException("unknown condition op: " + op.asText());
        }
    }

    private static List<String> nodeIds(JsonNode config, String field) {
        JsonNode value = config.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new ConditionSyntaxException("condition '" + field + "' must be an array of node ids");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode id : (ArrayNode) value) {
            if (!id.isTextual()) {
                throw new ConditionSyntaxException("condition '" + field + "' must contain node id strings");
            }
            ids.add(id.asText());
        }
        return ids;
    }
}
