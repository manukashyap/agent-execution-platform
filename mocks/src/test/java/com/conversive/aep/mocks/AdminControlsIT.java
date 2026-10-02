package com.conversive.aep.mocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminControlsIT extends MockIT {

    private static final Map<String, Object> SEND = Map.of("to", "a@b.test", "body", "hi");

    @Test
    void healthIsUp() throws Exception {
        assertThat(get("/actuator/health").body().get("status").asText()).isEqualTo("UP");
    }

    @Test
    void countsCallsPerRoute() throws Exception {
        post("/messaging/send", SEND);
        post("/messaging/send", SEND);
        get("/leads");

        var calls = get("/admin/calls").body();
        assertThat(calls.get("messaging.send").asLong()).isEqualTo(2);
        assertThat(calls.get("leads.fetch").asLong()).isEqualTo(1);
    }

    @Test
    void failRateIsDeterministicEveryNthCall() throws Exception {
        post("/admin/fail-rate", Map.of("route", "messaging.send", "percent", 25));

        List<Integer> statuses = statuses(8);

        assertThat(statuses).containsExactly(200, 200, 200, 500, 200, 200, 200, 500);
    }

    @Test
    void failRateUsesConfiguredStatusAndHundredPercentFailsAll() throws Exception {
        post("/admin/fail-rate", Map.of("route", "messaging.send", "percent", 100, "status", 503));

        assertThat(statuses(3)).containsExactly(503, 503, 503);
    }

    @Test
    void failRateRestartsCountingWhenReconfigured() throws Exception {
        post("/admin/fail-rate", Map.of("route", "messaging.send", "percent", 50));
        assertThat(statuses(2)).containsExactly(200, 500);

        post("/admin/fail-rate", Map.of("route", "messaging.send", "percent", 50));
        assertThat(statuses(2)).containsExactly(200, 500);
    }

    @Test
    void rateLimitReturns429WithRetryAfterForNextNCalls() throws Exception {
        post("/admin/rate-limit", Map.of("route", "messaging.send", "count", 2, "retryAfterS", 7));

        Reply first = post("/messaging/send", SEND);
        Reply second = post("/messaging/send", SEND);
        Reply third = post("/messaging/send", SEND);

        assertThat(first.status()).isEqualTo(429);
        assertThat(first.header("Retry-After")).isEqualTo("7");
        assertThat(second.status()).isEqualTo(429);
        assertThat(third.status()).isEqualTo(200);
    }

    @Test
    void dropConnectionClosesSocketWithoutResponseForNextNCalls() throws Exception {
        post("/admin/drop-connection", Map.of("route", "messaging.send", "count", 2));

        assertThatThrownBy(() -> post("/messaging/send", SEND)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> post("/messaging/send", SEND)).isInstanceOf(IOException.class);
        assertThat(post("/messaging/send", SEND).status()).isEqualTo(200);
    }

    @Test
    void latencyOverridesDefaultsAndIsReportedEffective() throws Exception {
        assertThat(get("/admin/latency").body().get("llm-b").asLong()).isEqualTo(500);

        post("/admin/latency", Map.of("route", "messaging.send", "ms", 300));
        long start = System.nanoTime();
        post("/messaging/send", SEND);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(300);
    }

    @Test
    void resetClearsKnobsCountersAndState() throws Exception {
        post("/admin/fail-rate", Map.of("route", "messaging.send", "percent", 100));
        post("/admin/rate-limit", Map.of("route", "leads.fetch", "count", 5, "retryAfterS", 1));
        post("/admin/latency", Map.of("route", "llm-b", "ms", 1));
        post("/messaging/send", SEND);

        post("/admin/reset", null);

        assertThat(get("/admin/calls").body()).isEmpty();
        assertThat(get("/admin/latency").body().get("llm-b").asLong()).isEqualTo(500);
        assertThat(post("/messaging/send", SEND).status()).isEqualTo(200);
        assertThat(get("/leads").status()).isEqualTo(200);
    }

    private List<Integer> statuses(int calls) throws Exception {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            out.add(post("/messaging/send", SEND).status());
        }
        return out;
    }
}
