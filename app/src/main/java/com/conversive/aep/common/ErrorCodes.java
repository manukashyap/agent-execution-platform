package com.conversive.aep.common;

/** Canonical error codes (06 §0). Used as {@code ApplicationFailure} types and API error codes. */
public final class ErrorCodes {

    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String CONCURRENCY_LIMIT = "CONCURRENCY_LIMIT";
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    public static final String TOKEN_BUDGET_EXCEEDED = "TOKEN_BUDGET_EXCEEDED";
    public static final String NODE_EXEC_LIMIT = "NODE_EXEC_LIMIT";
    public static final String FANOUT_LIMIT = "FANOUT_LIMIT";
    public static final String TOOL_CALL_LIMIT = "TOOL_CALL_LIMIT";

    public static final String EFFECT_IN_PROGRESS = "EFFECT_IN_PROGRESS";
    public static final String EFFECT_UNKNOWN = "EFFECT_UNKNOWN";
    /** The effect may or may not have happened and no reconciliation exists; a human must decide. */
    public static final String NEEDS_ATTENTION = "NEEDS_ATTENTION";
    public static final String TOOL_FORBIDDEN = "TOOL_FORBIDDEN";
    public static final String TOOL_NOT_FOUND = "TOOL_NOT_FOUND";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String START_FAILED = "START_FAILED";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String CANCELLED = "CANCELLED";

    public static final String UPSTREAM_TIMEOUT = "UPSTREAM_TIMEOUT";
    public static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";
    public static final String UPSTREAM_RATE_LIMITED = "UPSTREAM_RATE_LIMITED";
    public static final String UPSTREAM_CLIENT_ERROR = "UPSTREAM_CLIENT_ERROR";
    public static final String UPSTREAM_IO = "UPSTREAM_IO";
    public static final String EGRESS_DENIED = "EGRESS_DENIED";
    public static final String NO_PROVIDER_AVAILABLE = "NO_PROVIDER_AVAILABLE";
    public static final String INTERNAL = "INTERNAL";

    private ErrorCodes() {
    }
}
