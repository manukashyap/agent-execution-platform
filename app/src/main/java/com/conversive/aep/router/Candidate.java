package com.conversive.aep.router;

/**
 * How the router saw one provider for one request (persisted in {@code llm_call.candidates}).
 *
 * @param excludedBy null when eligible, otherwise a {@code RouteReasons.EXCLUDED_*} value
 * @param score      priority-weighted score (lower is better), null when excluded
 */
public record Candidate(String provider, HealthState state, String excludedBy, Double score) {

    public boolean eligible() {
        return excludedBy == null;
    }

    public Candidate withScore(double value) {
        return new Candidate(provider, state, excludedBy, value);
    }
}
