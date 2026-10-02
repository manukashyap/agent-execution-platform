package com.conversive.aep.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** Test clock that only moves when told to. */
public final class MutableClock extends Clock {

    private final AtomicLong millis;

    public MutableClock(Instant start) {
        this.millis = new AtomicLong(start.toEpochMilli());
    }

    /** Starts on a 10 s boundary so tests can reason about window edges. */
    public static MutableClock atWindowStart() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    public void advance(Duration d) {
        millis.addAndGet(d.toMillis());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public long millis() {
        return millis.get();
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }
}
