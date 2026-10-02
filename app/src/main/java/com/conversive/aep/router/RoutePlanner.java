package com.conversive.aep.router;

import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filter → score → choose (06 §4.7). The primary is, in order of precedence: a HALF_OPEN probe, the
 * DEGRADED probe share, the spill target when the spill-from provider's bucket is empty, the provider
 * serving the requested model hint, or the best score. Up to {@link #MAX_FALLBACKS} further eligible providers follow by score.
 */
public final class RoutePlanner {

    public static final int MAX_FALLBACKS = 2;

    private final List<ProviderRuntime> providers;
    private final Map<String, Set<String>> allowList;
    private final RouterProperties.Spill spill;
    private final PriorityScorer scorer = new PriorityScorer();

    public RoutePlanner(List<ProviderRuntime> providers, Map<String, List<String>> allowList, RouterProperties.Spill spill) {
        this.providers = providers.stream().sorted(Comparator.comparing(ProviderRuntime::name)).toList();
        this.allowList = allowList.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
        this.spill = spill;
    }

    public List<ProviderRuntime> providers() {
        return providers;
    }

    public RoutePlan plan(LlmRequest request) {
        List<Candidate> judged = new ArrayList<>(providers.stream().map(p -> judge(p, request)).toList());
        Optional<RoutePlan.Step> halfOpen = claimHalfOpenProbe(judged);
        List<ProviderRuntime> ranked = rank(request.priority(), judged);
        RoutePlan.Step primary = halfOpen
                .or(() -> degradedProbe(ranked))
                .or(() -> spillTarget(request.priority(), judged))
                .or(() -> hinted(ranked, request.modelHint()))
                .orElseGet(() -> ranked.isEmpty() ? null : new RoutePlan.Step(ranked.get(0), RouteReasons.BEST_SCORE, false));
        if (primary == null) {
            return new RoutePlan(List.of(), judged);
        }
        List<RoutePlan.Step> steps = new ArrayList<>();
        steps.add(primary);
        ranked.stream()
                .filter(p -> p != primary.provider())
                .limit(MAX_FALLBACKS)
                .forEach(p -> steps.add(new RoutePlan.Step(p, RouteReasons.FALLBACK_AFTER_ERROR, false)));
        return new RoutePlan(steps, judged);
    }

    private Candidate judge(ProviderRuntime p, LlmRequest request) {
        HealthState state = p.health().state();
        return new Candidate(p.name(), state, exclusion(p, state, request), null);
    }

    private String exclusion(ProviderRuntime p, HealthState state, LlmRequest request) {
        if (!p.config().capabilities().containsAll(request.capabilities())) {
            return RouteReasons.EXCLUDED_CAPABILITY;
        }
        if (!tenantAllows(request.tenantId(), p.name())) {
            return RouteReasons.EXCLUDED_TENANT;
        }
        if (state == HealthState.OPEN) {
            return RouteReasons.EXCLUDED_OPEN;
        }
        return p.bucket().hasToken() ? null : RouteReasons.EXCLUDED_BUCKET;
    }

    private boolean tenantAllows(TenantId tenantId, String provider) {
        Set<String> allowed = allowList.get(tenantId.value());
        return allowed == null || allowed.contains(provider);
    }

    /** At most one HALF_OPEN provider gets the request as a probe; the others are excluded for this request. */
    private Optional<RoutePlan.Step> claimHalfOpenProbe(List<Candidate> judged) {
        RoutePlan.Step claimed = null;
        for (int i = 0; i < judged.size(); i++) {
            Candidate c = judged.get(i);
            if (!c.eligible() || c.state() != HealthState.HALF_OPEN) {
                continue;
            }
            ProviderRuntime p = byName(c.provider());
            if (claimed == null && p.health().tryAcquireProbe()) {
                claimed = new RoutePlan.Step(p, RouteReasons.HALF_OPEN_PROBE, true);
            } else {
                judged.set(i, new Candidate(c.provider(), c.state(), RouteReasons.EXCLUDED_HALF_OPEN_BUSY, null));
            }
        }
        return Optional.ofNullable(claimed);
    }

    /** Scores HEALTHY and DEGRADED eligible providers (in place, for the candidates record) and returns them best first. */
    private List<ProviderRuntime> rank(Priority priority, List<Candidate> judged) {
        List<PriorityScorer.Input> inputs = judged.stream()
                .filter(c -> c.eligible() && (c.state() == HealthState.HEALTHY || c.state() == HealthState.DEGRADED))
                .map(c -> input(byName(c.provider()), c.state()))
                .toList();
        Map<String, Double> scores = scorer.score(priority, inputs);
        judged.replaceAll(c -> scores.containsKey(c.provider()) ? c.withScore(scores.get(c.provider())) : c);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> byName(e.getKey()))
                .toList();
    }

    /**
     * HEALTHY providers are scored on their nominal latency: scoring them on the observed p95 would move
     * traffic away after one slow window and starve the tracker of the samples it needs to detect
     * DEGRADED (and, later, recovery). Slowness is handled by the state machine; a DEGRADED provider
     * is scored on its observed p95 plus the penalty and keeps its probe share.
     */
    private static PriorityScorer.Input input(ProviderRuntime p, HealthState state) {
        HealthSnapshot s = p.health().snapshot();
        double latency = state == HealthState.DEGRADED && s.observed() ? s.p95Ms() : p.config().nominalLatency().toMillis();
        double errorRate = s.observed() ? s.errorRate() : 0;
        return new PriorityScorer.Input(p.name(), state, latency,
                p.config().costPerThousandTokensUsd().doubleValue(), errorRate);
    }

    private static Optional<RoutePlan.Step> degradedProbe(List<ProviderRuntime> ranked) {
        return ranked.stream()
                .filter(p -> p.health().takeDegradedProbe())
                .findFirst()
                .map(p -> new RoutePlan.Step(p, RouteReasons.PROBE, false));
    }

    private static Optional<RoutePlan.Step> hinted(List<ProviderRuntime> ranked, String modelHint) {
        if (modelHint == null || modelHint.isBlank()) {
            return Optional.empty();
        }
        return ranked.stream()
                .filter(p -> p.config().model().equals(modelHint))
                .findFirst()
                .map(p -> new RoutePlan.Step(p, RouteReasons.MODEL_HINT, false));
    }

    private Optional<RoutePlan.Step> spillTarget(Priority priority, List<Candidate> judged) {
        boolean sourceEmpty = judged.stream()
                .anyMatch(c -> c.provider().equals(spill.from()) && RouteReasons.EXCLUDED_BUCKET.equals(c.excludedBy()));
        if (!sourceEmpty) {
            return Optional.empty();
        }
        String target = priority == Priority.HIGH ? spill.highTo() : spill.defaultTo();
        return judged.stream()
                .filter(c -> c.provider().equals(target) && c.eligible() && c.state() != HealthState.HALF_OPEN)
                .findFirst()
                .map(c -> new RoutePlan.Step(byName(target), RouteReasons.SPILL_BUCKET_EMPTY, false));
    }

    private ProviderRuntime byName(String name) {
        return providers.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }
}
