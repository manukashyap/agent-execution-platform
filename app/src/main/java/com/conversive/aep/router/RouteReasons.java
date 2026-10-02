package com.conversive.aep.router;

/** Vocabulary of {@code router_decision} reasons (stored in {@code llm_call.reason}, later a metric label). */
public final class RouteReasons {

    /** Lowest priority-weighted score among eligible providers. */
    public static final String BEST_SCORE = "best_score";
    /** Deterministic probe share sent to a DEGRADED provider (every 20th eligible request). */
    public static final String PROBE = "probe";
    /** One of the probe calls that decide HALF_OPEN → HEALTHY / OPEN. */
    public static final String HALF_OPEN_PROBE = "half_open_probe";
    /** The spill-from provider (vLLM) had no token; HIGH goes to A, NORMAL/LOW to B. */
    public static final String SPILL_BUCKET_EMPTY = "spill_bucket_empty";
    /** An eligible provider serves the requested {@code model} hint. */
    public static final String MODEL_HINT = "model_hint";
    /** The previous provider of this route failed with a retryable error. */
    public static final String FALLBACK_AFTER_ERROR = "fallback_after_error";

    /** Candidate exclusion reasons (in {@code llm_call.candidates}). */
    public static final String EXCLUDED_CAPABILITY = "missing_capability";
    public static final String EXCLUDED_TENANT = "tenant_not_allowed";
    public static final String EXCLUDED_OPEN = "open";
    public static final String EXCLUDED_HALF_OPEN_BUSY = "half_open_probes_in_flight";
    public static final String EXCLUDED_BUCKET = "bucket_empty";

    private RouteReasons() {
    }
}
