package com.conversive.aep.engine.workflow.condition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Function;

/** Pure, deterministic evaluation of a {@link Condition}; safe to call from workflow code. */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    /**
     * @param valueOf output of a node by id, or the execution input for {@link ValuePath#INPUT};
     *                may return null for unknown roots
     */
    public static boolean evaluate(Condition condition, Function<String, JsonNode> valueOf) {
        JsonNode left = condition.left().resolve(valueOf.apply(condition.left().root()));
        boolean present = !left.isMissingNode() && !left.isNull();
        return switch (condition.op()) {
            case EXISTS -> present;
            case EQ -> present && same(left, condition.right());
            case NE -> !present || !same(left, condition.right());
            case GT -> present && compare(left, condition.right()) > 0;
            case GTE -> present && compare(left, condition.right()) >= 0 && comparable(left, condition.right());
            case LT -> present && compare(left, condition.right()) < 0 && comparable(left, condition.right());
            case LTE -> present && compare(left, condition.right()) <= 0 && comparable(left, condition.right());
        };
    }

    private static boolean same(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        return left.equals(right);
    }

    private static boolean comparable(JsonNode left, JsonNode right) {
        return (left.isNumber() && right.isNumber()) || (left.isTextual() && right.isTextual());
    }

    /** Numbers compare numerically, strings lexicographically; anything else compares as "not greater". */
    private static int compare(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue());
        }
        if (left.isTextual() && right.isTextual()) {
            return left.asText().compareTo(right.asText());
        }
        return 0;
    }
}
