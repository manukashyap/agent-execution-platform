package com.conversive.aep.mocks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SuppressWarnings({"unchecked", "rawtypes"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminControlsIT {

    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void reset() {
        assertThat(rest.postForEntity("/admin/reset", null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void healthIsUp() {
        assertThat(rest.getForObject("/actuator/health", Map.class)).containsEntry("status", "UP");
    }

    @Test
    void countsCallsPerRouteAndEchoesIdempotencyKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "k-1");
        ResponseEntity<Map> response = rest.exchange("/echo", HttpMethod.POST,
                new HttpEntity<>(Map.of("x", 1), headers), Map.class);
        rest.getForObject("/echo", Map.class);

        assertThat(response.getBody()).containsEntry("idempotencyKey", "k-1");
        assertThat(rest.getForObject("/admin/calls", Map.class)).containsEntry("echo", 2);
        assertThat(rest.getForObject("/admin/calls/echo", Map.class)).containsEntry("echo", 2);
    }

    @Test
    void failRateOfHundredPercentFailsEveryCallWith503() {
        rest.postForEntity("/admin/fail-rate", Map.of("route", "echo", "percent", 100), Map.class);

        assertThat(rest.getForEntity("/echo", String.class).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(rest.getForObject("/admin/calls", Map.class)).containsEntry("echo", 1);
    }

    @Test
    void latencyIsAppliedPerRoute() {
        rest.postForEntity("/admin/latency", Map.of("route", "echo", "ms", 300), Map.class);

        long start = System.nanoTime();
        rest.getForObject("/echo", Map.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(300);
        assertThat(rest.getForObject("/admin/latency", Map.class)).containsEntry("echo", 300);
    }

    @Test
    void resetClearsKnobsAndCounters() {
        rest.postForEntity("/admin/fail-rate", Map.of("route", "echo", "percent", 100), Map.class);
        rest.getForEntity("/echo", String.class);

        rest.postForEntity("/admin/reset", null, Void.class);

        assertThat(rest.getForObject("/admin/calls", Map.class)).isEmpty();
        assertThat(rest.getForEntity("/echo", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
