package com.conversive.aep.common.http;

import com.conversive.aep.common.ExecutionMode;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * One outbound HTTP call.
 *
 * @param timeout        hard deadline for the whole call (pool wait, connect, request and the entire response body;
 *                       the call is cancelled at this point); callers pass {@code node StartToClose - 1s}
 * @param idempotencyKey sent as {@code Idempotency-Key} when non-null
 * @param allowInNonLive set only by the dry-run policy for calls it explicitly permits outside LIVE mode
 */
public record OutboundRequest(
        String method,
        URI uri,
        Map<String, String> headers,
        JsonNode body,
        Duration timeout,
        String idempotencyKey,
        ExecutionMode mode,
        boolean allowInNonLive) {

    public OutboundRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(mode, "mode");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static OutboundRequest get(URI uri, Duration timeout, ExecutionMode mode) {
        return new OutboundRequest("GET", uri, Map.of(), null, timeout, null, mode, false);
    }

    public static OutboundRequest post(URI uri, JsonNode body, Duration timeout, ExecutionMode mode) {
        return new OutboundRequest("POST", uri, Map.of(), body, timeout, null, mode, false);
    }

    public OutboundRequest withIdempotencyKey(String key) {
        return new OutboundRequest(method, uri, headers, body, timeout, key, mode, allowInNonLive);
    }

    public OutboundRequest withHeaders(Map<String, String> newHeaders) {
        return new OutboundRequest(method, uri, newHeaders, body, timeout, idempotencyKey, mode, allowInNonLive);
    }

    public OutboundRequest withAllowInNonLive(boolean allow) {
        return new OutboundRequest(method, uri, headers, body, timeout, idempotencyKey, mode, allow);
    }
}
