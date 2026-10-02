package com.conversive.aep.router;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * Health state machine of one provider (06 §4.7). Samples fall into fixed windows; a window is
 * evaluated once, when the first call after its end rolls it over. Windows with fewer than
 * {@code minSamples} samples are ignored. "Consecutive" counts only qualifying windows inside the
 * lookback that closed after the last state change, so stale windows never re-trigger a transition.
 * All methods are synchronized; time comes only from the injected {@link Clock}.
 */
public final class ProviderHealthTracker {

    private record Window(long index, int samples, int errors, long p95Ms) {
        double errorRate() {
            return samples == 0 ? 0 : (double) errors / samples;
        }
    }

    private final HealthPolicy policy;
    private final Clock clock;
    private final long windowMillis;
    private final ArrayDeque<Window> closed = new ArrayDeque<>();
    private final List<Long> latencies = new ArrayList<>();
    private int errors;
    private long currentIndex;

    private HealthState state = HealthState.HEALTHY;
    private long transitionIndex = Long.MIN_VALUE;
    private Instant openedAt;
    private long degradedEligible;

    private int probesIssued;
    private final List<Long> probeLatencies = new ArrayList<>();
    private int probeFailures;

    public ProviderHealthTracker(HealthPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
        this.windowMillis = policy.window().toMillis();
        this.currentIndex = clock.millis() / windowMillis;
    }

    public synchronized void record(long latencyMs, boolean success) {
        roll();
        latencies.add(latencyMs);
        if (!success) {
            errors++;
        }
        if (state == HealthState.HALF_OPEN) {
            recordProbe(latencyMs, success);
        }
    }

    public synchronized HealthState state() {
        roll();
        return state;
    }

    public synchronized HealthSnapshot snapshot() {
        roll();
        for (Iterator<Window> it = closed.descendingIterator(); it.hasNext(); ) {
            Window w = it.next();
            if (qualifies(w)) {
                return new HealthSnapshot(state, w.p95Ms(), w.errorRate(), latencies.size());
            }
        }
        return new HealthSnapshot(state, -1, -1, latencies.size());
    }

    /** HALF_OPEN only: claims one of the probe slots. Release it with {@link #releaseProbe()} if no outcome is recorded. */
    public synchronized boolean tryAcquireProbe() {
        roll();
        if (state != HealthState.HALF_OPEN || probesIssued >= policy.halfOpenProbes()) {
            return false;
        }
        probesIssued++;
        return true;
    }

    public synchronized void releaseProbe() {
        if (state == HealthState.HALF_OPEN && probesIssued > probeLatencies.size()) {
            probesIssued--;
        }
    }

    /** DEGRADED only: counts one eligible request and returns true for every {@code degradedProbeEvery}-th. */
    public synchronized boolean takeDegradedProbe() {
        roll();
        if (state != HealthState.DEGRADED) {
            return false;
        }
        degradedEligible++;
        return degradedEligible % policy.degradedProbeEvery() == 0;
    }

    private void roll() {
        long now = clock.millis();
        long index = now / windowMillis;
        if (index > currentIndex) {
            close(new Window(currentIndex, latencies.size(), errors, p95(latencies)));
            for (long i = Math.max(currentIndex + 1, index - policy.lookbackWindows()); i < index; i++) {
                push(new Window(i, 0, 0, 0));
            }
            currentIndex = index;
            latencies.clear();
            errors = 0;
        }
        if (state == HealthState.OPEN && !clock.instant().isBefore(openedAt.plus(policy.openCooldown()))) {
            enter(HealthState.HALF_OPEN, currentIndex);
        }
    }

    private void close(Window w) {
        push(w);
        if (!qualifies(w)) {
            return;
        }
        if (state != HealthState.OPEN && (w.errorRate() > policy.openErrorRate() || w.p95Ms() > policy.openP95Ms())) {
            enter(HealthState.OPEN, w.index());
        } else if (state == HealthState.HEALTHY
                && lastQualifyingAll(policy.degradeWindows(), p95 -> p95 > policy.degradeP95Ms())) {
            enter(HealthState.DEGRADED, w.index());
        } else if (state == HealthState.DEGRADED
                && lastQualifyingAll(policy.recoverWindows(), p95 -> p95 <= policy.recoverP95Ms())) {
            enter(HealthState.HEALTHY, w.index());
        }
    }

    private boolean lastQualifyingAll(int count, LongPredicate test) {
        int seen = 0;
        for (Iterator<Window> it = closed.descendingIterator(); it.hasNext() && seen < count; ) {
            Window w = it.next();
            if (w.index() <= transitionIndex) {
                break;
            }
            if (!qualifies(w)) {
                continue;
            }
            if (!test.test(w.p95Ms())) {
                return false;
            }
            seen++;
        }
        return seen == count;
    }

    private void recordProbe(long latencyMs, boolean success) {
        probeLatencies.add(latencyMs);
        if (!success) {
            probeFailures++;
        }
        if (probeLatencies.size() < policy.halfOpenProbes()) {
            return;
        }
        boolean healthy = probeFailures <= policy.halfOpenMaxFailures() && p95(probeLatencies) <= policy.halfOpenMaxP95Ms();
        enter(healthy ? HealthState.HEALTHY : HealthState.OPEN, currentIndex);
    }

    private void enter(HealthState next, long atIndex) {
        state = next;
        transitionIndex = atIndex;
        degradedEligible = 0;
        probesIssued = 0;
        probeFailures = 0;
        probeLatencies.clear();
        if (next == HealthState.OPEN) {
            openedAt = clock.instant();
        }
    }

    private void push(Window w) {
        closed.addLast(w);
        while (closed.size() > policy.lookbackWindows()) {
            closed.removeFirst();
        }
    }

    private boolean qualifies(Window w) {
        return w.samples() >= policy.minSamples();
    }

    static long p95(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        int rank = (int) Math.ceil(0.95 * sorted.length) - 1;
        return sorted[Math.max(0, rank)];
    }
}
