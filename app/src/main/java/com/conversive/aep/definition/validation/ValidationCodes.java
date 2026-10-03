package com.conversive.aep.definition.validation;

/** Codes of individual validator findings (reported in {@code error.details} under {@code VALIDATION_FAILED}). */
public final class ValidationCodes {

    // errors
    public static final String MISSING_FIELD = "MISSING_FIELD";
    public static final String DUPLICATE_NODE_ID = "DUPLICATE_NODE_ID";
    public static final String INVALID_NODE_ID = "INVALID_NODE_ID";
    public static final String CYCLE = "CYCLE";
    public static final String MISSING_DEPENDENCY = "MISSING_DEPENDENCY";
    public static final String TOO_MANY_NODES = "TOO_MANY_NODES";
    public static final String WIDTH_EXCEEDED = "WIDTH_EXCEEDED";
    public static final String FANOUT_CONCURRENCY_EXCEEDED = "FANOUT_CONCURRENCY_EXCEEDED";
    public static final String FOR_EACH_INVALID = "FOR_EACH_INVALID";
    public static final String UNKNOWN_NODE_TYPE = "UNKNOWN_NODE_TYPE";
    public static final String UNKNOWN_TOOL = "UNKNOWN_TOOL";
    public static final String AI_TOOL_NOT_READ_ONLY = "AI_TOOL_NOT_READ_ONLY";
    public static final String INVALID_CONFIG = "INVALID_CONFIG";
    public static final String CONDITION_INVALID = "CONDITION_INVALID";
    public static final String CONDITION_BRANCH_INVALID = "CONDITION_BRANCH_INVALID";
    public static final String TIMEOUT_OUT_OF_RANGE = "TIMEOUT_OUT_OF_RANGE";
    public static final String RETRY_OUT_OF_RANGE = "RETRY_OUT_OF_RANGE";
    public static final String SCHEDULE_TO_CLOSE_TOO_SHORT = "SCHEDULE_TO_CLOSE_TOO_SHORT";
    public static final String SIDE_EFFECT_NEEDS_THREE_ATTEMPTS = "SIDE_EFFECT_NEEDS_THREE_ATTEMPTS";
    public static final String LIMIT_ABOVE_CEILING = "LIMIT_ABOVE_CEILING";
    public static final String INVALID_LIMIT = "INVALID_LIMIT";
    public static final String COMPENSATE_WITHOUT_SIDE_EFFECT = "COMPENSATE_WITHOUT_SIDE_EFFECT";

    // warnings
    public static final String COMPENSATABLE_AFTER_PIVOT = "COMPENSATABLE_AFTER_PIVOT";
    public static final String MULTIPLE_PIVOTS_ON_PATH = "MULTIPLE_PIVOTS_ON_PATH";
    public static final String SIDE_EFFECT_WITHOUT_COMPENSATION = "SIDE_EFFECT_WITHOUT_COMPENSATION";

    private ValidationCodes() {
    }
}
