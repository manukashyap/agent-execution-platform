package com.conversive.aep.sideeffect;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 06 §4.9 timing contract (PDF §9: StartToClose 10 s, provider answers at 15 s).
 * The client gives up before Temporal times the attempt out, and the lease outlives the attempt,
 * so a retry never races the original call; a late success is learned only through reconciliation.
 */
public final class TimingContract {

    public static final Duration HTTP_TIMEOUT_MARGIN = Duration.ofSeconds(1);
    public static final Duration LEASE_GRACE = Duration.ofSeconds(5);

    private TimingContract() {
    }

    /** {@code StartToClose − 1 s}; for StartToClose ≤ 1 s (tests only) half of it, so it stays positive. */
    public static Duration httpTimeout(Duration startToClose) {
        requirePositive(startToClose);
        return startToClose.compareTo(HTTP_TIMEOUT_MARGIN) > 0
                ? startToClose.minus(HTTP_TIMEOUT_MARGIN)
                : startToClose.dividedBy(2);
    }

    /** {@code StartToClose + 5 s}: how long one attempt owns a PENDING ledger row. */
    public static Duration lease(Duration startToClose) {
        requirePositive(startToClose);
        return startToClose.plus(LEASE_GRACE);
    }

    public static Instant leaseUntil(Instant now, Duration startToClose) {
        return now.plus(lease(startToClose));
    }

    /** R3-5: an {@code EFFECT_IN_PROGRESS} retry is only scheduled if ScheduleToClose leaves room for the lease. */
    public static Duration minScheduleToClose(Duration startToClose) {
        return startToClose.plus(lease(startToClose));
    }

    private static void requirePositive(Duration startToClose) {
        Objects.requireNonNull(startToClose, "startToClose");
        if (startToClose.isNegative() || startToClose.isZero()) {
            throw new IllegalArgumentException("startToClose must be positive");
        }
    }
}
