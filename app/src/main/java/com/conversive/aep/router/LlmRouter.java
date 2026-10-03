package com.conversive.aep.router;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.cost.BudgetService;
import com.conversive.aep.cost.Reservation;
import com.conversive.aep.observability.AepMetrics;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Routes one LLM request (one activity attempt): plan, then call the primary and, on a retryable
 * failure, up to two fallbacks within the request's timeout. Every provider call is wrapped in a
 * budget reservation, feeds the provider's health tracker and is recorded as one {@code llm_call} row.
 */
public class LlmRouter {

    private static final int CHARS_PER_TOKEN = 4;
    private static final int COMPLETION_TOKEN_ESTIMATE = 256;

    private final RoutePlanner planner;
    private final LlmProviderClient client;
    private final BudgetService budget;
    private final LlmCallRecorder recorder;
    private final RecentDecisions decisions;
    private final Duration noProviderRetryDelay;
    private final LongSupplier nanoTicker;
    private final AepMetrics metrics;

    public LlmRouter(RoutePlanner planner, LlmProviderClient client, BudgetService budget, LlmCallRecorder recorder,
            RecentDecisions decisions, Duration noProviderRetryDelay, LongSupplier nanoTicker, AepMetrics metrics) {
        this.planner = planner;
        this.client = client;
        this.budget = budget;
        this.recorder = recorder;
        this.decisions = decisions;
        this.noProviderRetryDelay = noProviderRetryDelay;
        this.nanoTicker = nanoTicker;
        this.metrics = metrics;
    }

    private record Route(LlmRequest request, RoutePlan plan, List<LlmResponse.ProviderAttempt> attempts) {
    }

    public LlmResponse route(LlmRequest request) {
        RoutePlan plan = planner.plan(request);
        if (plan.steps().isEmpty()) {
            throw noProvider(plan);
        }
        Route route = new Route(request, plan, new ArrayList<>());
        long deadline = nanoTicker.getAsLong() + request.timeout().toNanos();
        RetryableError last = null;
        for (int i = 0; i < plan.steps().size(); i++) {
            RoutePlan.Step step = plan.steps().get(i);
            Duration remaining = Duration.ofNanos(deadline - nanoTicker.getAsLong());
            if (remaining.isNegative() || remaining.isZero() || !step.provider().bucket().tryAcquire()) {
                releaseUnusedProbe(step);
                continue;
            }
            String reason = route.attempts().isEmpty() ? step.reason() : RouteReasons.FALLBACK_AFTER_ERROR;
            try {
                return callOnce(route, step, reason, remaining);
            } catch (RetryableError e) {
                last = e;
            }
        }
        throw new RetryableError(ErrorCodes.LLM_UNAVAILABLE,
                "no provider completed node " + request.nodeId() + " (" + route.attempts().size() + " calls)", null, last);
    }

    public List<ProviderHealthView> health() {
        return planner.providers().stream().map(ProviderRuntime::view).toList();
    }

    public RecentDecisions recentDecisions() {
        return decisions;
    }

    private LlmResponse callOnce(Route route, RoutePlan.Step step, String reason, Duration timeout) {
        ProviderRuntime provider = step.provider();
        decisions.record(provider.name(), reason);
        metrics.routerDecision(provider.name(), reason);
        boolean outcomeRecorded = false;
        try {
            Reservation reservation = reserve(route, provider.config());
            long start = nanoTicker.getAsLong();
            try {
                LlmProviderClient.Reply reply = client.complete(provider.config(), call(route.request(), provider, timeout));
                long latency = elapsedMs(start);
                provider.health().record(latency, true);
                outcomeRecorded = true;
                return succeeded(route, provider, reason, reservation, reply, latency);
            } catch (RuntimeException e) {
                long latency = elapsedMs(start);
                if (e instanceof RetryableError) {
                    provider.health().record(latency, false);
                    outcomeRecorded = true;
                }
                failed(route, provider, reason, reservation, e, latency);
                throw e;
            }
        } finally {
            if (!outcomeRecorded) {
                releaseUnusedProbe(step);
            }
        }
    }

    private static LlmProviderClient.Call call(LlmRequest request, ProviderRuntime provider, Duration timeout) {
        return new LlmProviderClient.Call(provider.config().model(), request.messages(), request.tools(), timeout,
                request.mode());
    }

    private Reservation reserve(Route route, ProviderConfig provider) {
        LlmRequest r = route.request();
        long promptChars = r.messages().stream().mapToLong(m -> m.content() == null ? 0 : m.content().length()).sum();
        BigDecimal estimate = provider.costFor(promptChars / CHARS_PER_TOKEN + COMPLETION_TOKEN_ESTIMATE);
        String ref = "llm:" + r.nodeId() + ":" + r.callIndex() + ":" + r.attempt() + ":" + r.turn()
                + ":" + route.attempts().size();
        return budget.tryReserve(r.tenantId(), r.executionId(), estimate, ref);
    }

    private LlmResponse succeeded(Route route, ProviderRuntime provider, String reason, Reservation reservation,
            LlmProviderClient.Reply reply, long latencyMs) {
        BigDecimal cost = provider.config().costFor(reply.totalTokens());
        budget.confirm(reservation, cost);
        record(route, provider, reason, reply.promptTokens(), reply.completionTokens(), cost, latencyMs, null);
        return new LlmResponse(provider.name(), provider.config().model(), reply.content(), reply.toolCalls(),
                reply.finishReason(), reply.promptTokens(), reply.completionTokens(), cost, reason, route.attempts());
    }

    private void failed(Route route, ProviderRuntime provider, String reason, Reservation reservation,
            RuntimeException error, long latencyMs) {
        budget.cancel(reservation);
        record(route, provider, reason, 0, 0, BigDecimal.ZERO, latencyMs, errorCode(error));
    }

    private void record(Route route, ProviderRuntime provider, String reason, int promptTokens, int completionTokens,
            BigDecimal cost, long latencyMs, String errorCode) {
        LlmRequest r = route.request();
        String outcome = errorCode == null ? LlmCallRecord.SUCCEEDED : LlmCallRecord.FAILED;
        recorder.record(new LlmCallRecord(r.tenantId(), r.executionId(), r.nodeId(), r.callIndex(), r.attempt(),
                r.turn(), route.attempts().size(), provider.name(), provider.config().model(), r.priority(),
                route.plan().candidates(), reason, promptTokens, completionTokens, cost, latencyMs, outcome, errorCode));
        metrics.llmCall(r.tenantId(), provider.name(), provider.config().model(), promptTokens, completionTokens, cost,
                Duration.ofMillis(latencyMs), errorCode);
        route.attempts().add(new LlmResponse.ProviderAttempt(provider.name(), reason, outcome, errorCode, latencyMs));
    }

    private static void releaseUnusedProbe(RoutePlan.Step step) {
        if (step.halfOpenProbe()) {
            step.provider().health().releaseProbe();
        }
    }

    private RuntimeException noProvider(RoutePlan plan) {
        String why = plan.candidates().stream()
                .map(c -> c.provider() + "=" + c.excludedBy())
                .collect(Collectors.joining(", "));
        String message = "no eligible LLM provider: " + why;
        return plan.hasTransientExclusion()
                ? new RetryableError(ErrorCodes.NO_PROVIDER_AVAILABLE, message, noProviderRetryDelay)
                : new NonRetryableError(ErrorCodes.NO_PROVIDER_AVAILABLE, message);
    }

    private long elapsedMs(long startNanos) {
        return Math.max(0, (nanoTicker.getAsLong() - startNanos) / 1_000_000);
    }

    private static String errorCode(RuntimeException e) {
        if (e instanceof RetryableError r) {
            return r.code();
        }
        if (e instanceof NonRetryableError n) {
            return n.code();
        }
        return ErrorCodes.INTERNAL;
    }
}
