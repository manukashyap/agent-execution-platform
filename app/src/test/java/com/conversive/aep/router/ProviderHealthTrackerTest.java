package com.conversive.aep.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class ProviderHealthTrackerTest {

    private static final Duration WINDOW = Duration.ofSeconds(10);

    private final MutableClock clock = MutableClock.atWindowStart();
    private final ProviderHealthTracker tracker = new ProviderHealthTracker(HealthPolicy.defaults(), clock);

    /** Records {@code samples} calls in the current window, then moves to the next window and lets it roll over. */
    private HealthState window(int samples, long latencyMs, int failures) {
        for (int i = 0; i < samples; i++) {
            tracker.record(latencyMs, i >= failures);
        }
        clock.advance(WINDOW);
        return tracker.state();
    }

    private void degrade() {
        window(20, 3_000, 0);
        assertThat(window(20, 3_000, 0)).isEqualTo(HealthState.DEGRADED);
    }

    private void open() {
        assertThat(window(20, 100, 11)).isEqualTo(HealthState.OPEN);
    }

    @Test
    void degradesAfterTwoConsecutiveQualifyingWindowsOverTwoSeconds() {
        assertThat(window(20, 3_000, 0)).isEqualTo(HealthState.HEALTHY);
        assertThat(window(20, 3_000, 0)).isEqualTo(HealthState.DEGRADED);
    }

    @Test
    void aFastWindowBreaksTheDegradeStreak() {
        window(20, 3_000, 0);
        window(20, 500, 0);
        assertThat(window(20, 3_000, 0)).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    void windowsWithFewerThanTwentySamplesChangeNothing() {
        assertThat(window(19, 3_000, 0)).isEqualTo(HealthState.HEALTHY);
        assertThat(window(19, 3_000, 0)).isEqualTo(HealthState.HEALTHY);
        assertThat(window(19, 9_000, 19)).isEqualTo(HealthState.HEALTHY);
        assertThat(tracker.snapshot().observed()).isFalse();
    }

    @Test
    void aSparseWindowNeitherCountsNorBreaksAStreak() {
        window(20, 3_000, 0);
        window(5, 100, 0);
        assertThat(window(20, 3_000, 0)).isEqualTo(HealthState.DEGRADED);
    }

    @Test
    void recoversAfterThreeQualifyingWindowsAtOrUnderFifteenHundredMs() {
        degrade();
        assertThat(window(20, 1_500, 0)).isEqualTo(HealthState.DEGRADED);
        assertThat(window(20, 1_000, 0)).isEqualTo(HealthState.DEGRADED);
        assertThat(window(20, 1_000, 0)).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    void recoveryNeedsWindowsClosedAfterTheTransition() {
        degrade();
        assertThat(window(20, 1_000, 0)).isEqualTo(HealthState.DEGRADED);
        assertThat(window(20, 1_800, 0)).isEqualTo(HealthState.DEGRADED);
        assertThat(window(20, 1_000, 0)).isEqualTo(HealthState.DEGRADED);
        assertThat(window(20, 1_000, 0)).isEqualTo(HealthState.DEGRADED);
        assertThat(window(20, 1_000, 0)).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    void opensOnErrorRateOverHalf() {
        assertThat(window(20, 100, 10)).isEqualTo(HealthState.HEALTHY);
        assertThat(window(20, 100, 11)).isEqualTo(HealthState.OPEN);
    }

    @Test
    void opensOnP95OverFiveSecondsEvenWhenDegraded() {
        degrade();
        assertThat(window(20, 6_000, 0)).isEqualTo(HealthState.OPEN);
    }

    @Test
    void halfOpensThirtySecondsAfterOpening() {
        open();
        clock.advance(Duration.ofSeconds(29));
        assertThat(tracker.state()).isEqualTo(HealthState.OPEN);
        clock.advance(Duration.ofSeconds(1));
        assertThat(tracker.state()).isEqualTo(HealthState.HALF_OPEN);
    }

    @Test
    void halfOpenHandsOutTenProbesAndClosesOnHealthyOutcomes() {
        halfOpen();
        assertThat(IntStream.range(0, 11).filter(i -> tracker.tryAcquireProbe()).count()).isEqualTo(10);

        for (int i = 0; i < 10; i++) {
            tracker.record(200, i >= 2);
        }

        assertThat(tracker.state()).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    void halfOpenReopensOnTooManyFailedProbes() {
        halfOpen();
        for (int i = 0; i < 10; i++) {
            tracker.record(200, i >= 3);
        }
        assertThat(tracker.state()).isEqualTo(HealthState.OPEN);
    }

    @Test
    void halfOpenReopensOnSlowProbes() {
        halfOpen();
        for (int i = 0; i < 10; i++) {
            tracker.record(2_500, true);
        }
        assertThat(tracker.state()).isEqualTo(HealthState.OPEN);
    }

    @Test
    void releasedProbeSlotsCanBeClaimedAgain() {
        halfOpen();
        for (int i = 0; i < 10; i++) {
            tracker.tryAcquireProbe();
        }
        assertThat(tracker.tryAcquireProbe()).isFalse();

        tracker.releaseProbe();

        assertThat(tracker.tryAcquireProbe()).isTrue();
    }

    @Test
    void degradedTakesEveryTwentiethEligibleRequestAsAProbe() {
        assertThat(tracker.takeDegradedProbe()).isFalse();
        degrade();

        List<Integer> probes = IntStream.rangeClosed(1, 40).filter(i -> tracker.takeDegradedProbe()).boxed().toList();

        assertThat(probes).containsExactly(20, 40);
    }

    @Test
    void snapshotReportsTheLatestQualifyingWindow() {
        window(20, 400, 5);
        HealthSnapshot s = tracker.snapshot();
        assertThat(s.observed()).isTrue();
        assertThat(s.p95Ms()).isEqualTo(400);
        assertThat(s.errorRate()).isEqualTo(0.25);
    }

    @Test
    void p95UsesNearestRank() {
        assertThat(ProviderHealthTracker.p95(LongStream.rangeClosed(1, 100).boxed().toList())).isEqualTo(95);
        assertThat(ProviderHealthTracker.p95(LongStream.rangeClosed(1, 20).boxed().toList())).isEqualTo(19);
        assertThat(ProviderHealthTracker.p95(List.of())).isZero();
    }

    private void halfOpen() {
        open();
        clock.advance(Duration.ofSeconds(30));
        assertThat(tracker.state()).isEqualTo(HealthState.HALF_OPEN);
    }
}
