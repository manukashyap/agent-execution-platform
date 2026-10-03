package com.conversive.aep.common.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.RetryableError;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The request timeout bounds the whole call, not each socket read. */
class OutboundClientDeadlineTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final Duration MARGIN = Duration.ofMillis(400);

    @Test
    void slowDripBodyIsCutOffAtTheCallDeadlineEvenThoughEveryReadIsFast() throws Exception {
        // One byte per 100 ms: every read beats the 500 ms read timeout, the 200-byte body needs 20 s.
        try (DripServer server = new DripServer("x".repeat(200), 100)) {
            OutboundClient client = client(server.port());
            long start = System.nanoTime();

            assertThatThrownBy(() -> client.send(OutboundRequest.get(uri(server.port()), TIMEOUT, ExecutionMode.LIVE)))
                    .isInstanceOfSatisfying(RetryableError.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_TIMEOUT));

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(TIMEOUT.plus(MARGIN));
        }
    }

    @Test
    void aCallThatFinishesInTimeIsNotCancelled() throws Exception {
        try (DripServer server = new DripServer("{\"a\":1}", 10)) {
            OutboundClient client = client(server.port());

            OutboundResponse response = client.send(OutboundRequest.get(uri(server.port()), Duration.ofSeconds(2),
                    ExecutionMode.LIVE));

            assertThat(response.body().get("a").asInt()).isEqualTo(1);
        }
    }

    private static OutboundClient client(int port) {
        return new OutboundClient(new OutboundProperties(List.of("localhost:" + port), List.of(), Duration.ofSeconds(1)),
                new ObjectMapper(), Clock.systemUTC());
    }

    private static URI uri(int port) {
        return URI.create("http://localhost:" + port + "/drip");
    }
}
