package com.conversive.aep.mocks.admin;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Per-route knobs (latency, failure rate) and hit counters shared by every mock route. */
@Component
public class MockControls {

    private final Map<String, Long> latencyMs = new ConcurrentHashMap<>();
    private final Map<String, Integer> failRatePercent = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> calls = new ConcurrentHashMap<>();

    /** Call at the start of every mock handler: counts the hit, applies latency, then maybe fails. */
    public void enter(String route) {
        calls.computeIfAbsent(route, r -> new AtomicLong()).incrementAndGet();
        sleep(latencyMs.getOrDefault(route, 0L));
        int percent = failRatePercent.getOrDefault(route, 0);
        if (percent > 0 && ThreadLocalRandom.current().nextInt(100) < percent) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "injected failure on " + route);
        }
    }

    public void setLatency(String route, long ms) {
        latencyMs.put(route, Math.max(0, ms));
    }

    public void setFailRate(String route, int percent) {
        failRatePercent.put(route, Math.clamp(percent, 0, 100));
    }

    public Map<String, Long> latencies() {
        return new TreeMap<>(latencyMs);
    }

    public Map<String, Integer> failRates() {
        return new TreeMap<>(failRatePercent);
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

    public void reset() {
        latencyMs.clear();
        failRatePercent.clear();
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
