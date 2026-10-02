package com.conversive.aep.common.http;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.RetryableError;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The only HTTP egress of the platform (06 §2 rule 3). */
@Component
public class OutboundClient {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final HttpClient http;
    private final EgressPolicy policy;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboundClient(OutboundProperties properties, ObjectMapper mapper, Clock clock) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.policy = new EgressPolicy(properties);
        this.mapper = mapper;
        this.clock = clock;
    }

    public OutboundResponse send(OutboundRequest request) {
        policy.check(request.uri(), request.mode(), request.allowInNonLive() || NonLiveEgress.permitted());
        HttpResponse<String> response = exchange(toHttpRequest(request));
        OutboundResponse result = new OutboundResponse(
                response.statusCode(), response.headers().map(), parse(response.body()));
        return classify(request, result);
    }

    private HttpRequest toHttpRequest(OutboundRequest request) {
        HttpRequest.BodyPublisher publisher = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(write(request.body()));
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(request.timeout())
                .header("Accept", "application/json")
                .method(request.method(), publisher);
        if (request.body() != null) {
            builder.header("Content-Type", "application/json");
        }
        request.headers().forEach(builder::header);
        if (request.idempotencyKey() != null) {
            builder.header(IDEMPOTENCY_KEY, request.idempotencyKey());
        }
        return builder.build();
    }

    private HttpResponse<String> exchange(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new RetryableError(ErrorCodes.UPSTREAM_TIMEOUT, "timeout calling " + request.uri().getHost(), null, e);
        } catch (IOException e) {
            throw new RetryableError(ErrorCodes.UPSTREAM_IO, "I/O error calling " + request.uri().getHost(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableError(ErrorCodes.UPSTREAM_IO, "interrupted calling " + request.uri().getHost(), null, e);
        }
    }

    private OutboundResponse classify(OutboundRequest request, OutboundResponse response) {
        int status = response.status();
        String target = request.method() + " " + request.uri().getHost() + " -> " + status;
        if (status == 429) {
            throw new RetryableError(ErrorCodes.UPSTREAM_RATE_LIMITED, target, retryAfter(response).orElse(null));
        }
        if (status >= 500) {
            throw new RetryableError(ErrorCodes.UPSTREAM_UNAVAILABLE, target, retryAfter(response).orElse(null));
        }
        if (status >= 400) {
            throw new UpstreamClientError(status, response.body(), target);
        }
        return response;
    }

    Optional<Duration> retryAfter(OutboundResponse response) {
        return response.header("Retry-After").flatMap(this::parseRetryAfter);
    }

    private Optional<Duration> parseRetryAfter(String value) {
        String trimmed = value.trim();
        try {
            return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(trimmed))));
        } catch (NumberFormatException ignored) {
            // not delta-seconds; try the HTTP-date form below
        }
        try {
            Instant at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(clock.instant(), at);
            return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.nullNode();
        }
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            return mapper.getNodeFactory().textNode(body);
        }
    }

    private String write(JsonNode body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED, "request body is not serialisable", e);
        }
    }
}
