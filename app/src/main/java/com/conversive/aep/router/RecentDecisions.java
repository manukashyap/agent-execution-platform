package com.conversive.aep.router;

import java.time.Clock;
import java.time.Duration;
import java.util.Deque;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/** Rolling count of router decisions (provider, reason) over a short horizon, for the dev state endpoint. */
public final class RecentDecisions {

    private record Entry(long atMillis, String provider, String reason) {
    }

    private final Deque<Entry> entries = new ConcurrentLinkedDeque<>();
    private final Clock clock;
    private final long horizonMillis;

    public RecentDecisions(Clock clock, Duration horizon) {
        this.clock = clock;
        this.horizonMillis = horizon.toMillis();
    }

    public void record(String provider, String reason) {
        entries.addLast(new Entry(clock.millis(), provider, reason));
        prune();
    }

    /** provider → reason → count within the horizon. */
    public Map<String, Map<String, Long>> counts() {
        prune();
        return entries.stream().collect(Collectors.groupingBy(Entry::provider, TreeMap::new,
                Collectors.groupingBy(Entry::reason, TreeMap::new, Collectors.counting())));
    }

    private void prune() {
        long cutoff = clock.millis() - horizonMillis;
        Entry head;
        while ((head = entries.peekFirst()) != null && head.atMillis() < cutoff) {
            entries.pollFirst();
        }
    }
}
