package com.conversive.aep.router;

import java.util.List;

/**
 * Ordered providers to try for one request: the primary (with its decision reason) and at most two
 * fallbacks, plus how every provider was judged.
 */
public record RoutePlan(List<Step> steps, List<Candidate> candidates) {

    public RoutePlan {
        steps = List.copyOf(steps);
        candidates = List.copyOf(candidates);
    }

    /** @param halfOpenProbe the step holds a HALF_OPEN probe slot that must be released if no outcome is recorded */
    public record Step(ProviderRuntime provider, String reason, boolean halfOpenProbe) {
    }

    public boolean hasTransientExclusion() {
        return candidates.stream().anyMatch(c -> RouteReasons.EXCLUDED_OPEN.equals(c.excludedBy())
                || RouteReasons.EXCLUDED_HALF_OPEN_BUSY.equals(c.excludedBy())
                || RouteReasons.EXCLUDED_BUCKET.equals(c.excludedBy()));
    }
}
