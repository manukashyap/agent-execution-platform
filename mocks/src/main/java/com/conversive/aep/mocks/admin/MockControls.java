package com.conversive.aep.mocks.admin;

import com.conversive.aep.mocks.support.DropConnectionException;
import com.conversive.aep.mocks.support.MockHttpException;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Per-route knobs (latency, failure rate, rate limit, connection drops) and hit counters.
 * Failure injection is counter-based, never random, so runs are reproducible.
 */
@Component
public class MockControls implements Resettable {

    private static final int DEFAULT_FAIL_STATUS = 500;
    private static final Map<String, Long> DEFAULT_LATENCY_MS = Map.of("llm-a", 200L, "llm-b", 500L, "vllm", 100L);

    public record FailRate(int percent, int status) {
    }

    private record RateLimit(AtomicInteger remaining, int retryAfterS) {
    }

    private final Map<String, Long> latencyMs = new ConcurrentHashMap<>();
    private final Map<String, FailRate> failRates = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> failCounters = new ConcurrentHashMap<>();
    private final Map<String, RateLimit> rateLimits = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> drops = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> calls = new ConcurrentHashMap<>();

    /** Count the hit, then apply drop / rate-limit / latency / failure injection in that order. */
    public void enter(String route) {
        admit(route, true);
    }

    /** Like {@link #enter} but leaves latency to the caller (payments apply it to async processing). */
    public void admitWithoutLatency(String route) {
        admit(route, false);
    }

    public void applyLatency(String route) {
        sleep(latencyFor(route));
    }

    private void admit(String route, boolean withLatency) {
        calls.computeIfAbsent(route, r -> new AtomicLong()).incrementAndGet();
        AtomicInteger drop = drops.get(route);
        if (drop != null && consume(drop)) {
            throw new DropConnectionException(route);
        }
        RateLimit limit = rateLimits.get(route);
        if (limit != null && consume(limit.remaining())) {
            throw new MockHttpException(429, "rate_limited", Map.of("Retry-After", String.valueOf(limit.retryAfterS())));
        }
        if (withLatency) {
            applyLatency(route);
        }
        FailRate failRate = failRates.get(route);
        if (failRate != null && shouldFail(route, failRate.percent())) {
            throw new MockHttpException(failRate.status(), "injected_failure");
        }
    }

    /** Deterministic: call n fails iff floor(n*p/100) exceeds floor((n-1)*p/100), i.e. every (100/p)-th call. */
    private boolean shouldFail(String route, int percent) {
        long n = failCounters.computeIfAbsent(route, r -> new AtomicLong()).incrementAndGet();
        return n * percent / 100 > (n - 1) * percent / 100;
    }

    private static boolean consume(AtomicInteger remaining) {
        return remaining.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0;
    }

    public void setLatency(String route, long ms) {
        latencyMs.put(route, Math.max(0, ms));
    }

    public void setFailRate(String route, int percent, Integer status) {
        failRates.put(route, new FailRate(Math.clamp(percent, 0, 100), status == null ? DEFAULT_FAIL_STATUS : status));
        failCounters.remove(route);
    }

    public void setRateLimit(String route, int count, int retryAfterS) {
        rateLimits.put(route, new RateLimit(new AtomicInteger(Math.max(0, count)), Math.max(0, retryAfterS)));
    }

    public void setDropConnection(String route, int count) {
        drops.put(route, new AtomicInteger(Math.max(0, count)));
    }

    public long latencyFor(String route) {
        return latencyMs.getOrDefault(route, DEFAULT_LATENCY_MS.getOrDefault(route, 0L));
    }

    public Map<String, Long> latencies() {
        Map<String, Long> effective = new TreeMap<>(DEFAULT_LATENCY_MS);
        effective.putAll(latencyMs);
        return effective;
    }

    public Map<String, FailRate> failRates() {
        return new TreeMap<>(failRates);
    }

    public Map<String, Long> calls() {
        Map<String, Long> snapshot = new TreeMap<>();
        calls.forEach((route, count) -> snapshot.put(route, count.get()));
        return snapshot;
    }

    public long calls(String route) {
        AtomicLong count = calls.get(route);
        return count == null ? 0 : count.get();
    }

    @Override
    public void reset() {
        latencyMs.clear();
        failRates.clear();
        failCounters.clear();
        rateLimits.clear();
        drops.clear();
        calls.clear();
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(Duration.ofMillis(ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
