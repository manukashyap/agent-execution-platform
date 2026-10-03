package com.conversive.aep.tools.mcp;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.http.OutboundClient;
import com.conversive.aep.common.http.OutboundRequest;
import com.conversive.aep.common.http.UpstreamClientError;
import com.conversive.aep.sideeffect.ProviderInFlightException;
import com.conversive.aep.tools.ToolsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * JSON-RPC 2.0 {@code tools/call} transport over {@link OutboundClient} (the only egress path). Maps protocol answers
 * onto the error taxonomy; the credential travels only as the {@code Authorization} header and is never echoed.
 */
@Component
public class McpClient {

    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    private static final int CONFLICT = 409;
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int CLIENT_ERROR_MIN = 400;
    private static final int SERVER_ERROR_MIN = 500;

    private final OutboundClient outbound;
    private final URI endpoint;
    private final AtomicLong ids = new AtomicLong();

    public McpClient(OutboundClient outbound, ToolsProperties properties) {
        this.outbound = outbound;
        this.endpoint = properties.mcpUrl();
    }

    /** @param idempotencyKey sent as {@code Idempotency-Key} when non-null (guarded tools) */
    public JsonNode callTool(String toolName, JsonNode args, String idempotencyKey, Optional<String> credential,
                             Duration timeout, ExecutionMode mode) {
        OutboundRequest request = OutboundRequest.post(endpoint, envelope(toolName, args), timeout, mode)
                .withIdempotencyKey(idempotencyKey)
                .withHeaders(credential.map(c -> Map.of("Authorization", "Bearer " + c)).orElse(Map.of()));
        JsonNode body = outbound.send(request).body();
        return unwrap(toolName, body);
    }

    private ObjectNode envelope(String toolName, JsonNode args) {
        ObjectNode params = JsonNodeFactory.instance.objectNode().put("name", toolName);
        params.set("arguments", args);
        ObjectNode rpc = JsonNodeFactory.instance.objectNode()
                .put("jsonrpc", "2.0")
                .put("id", ids.incrementAndGet())
                .put("method", "tools/call");
        rpc.set("params", params);
        return rpc;
    }

    static JsonNode unwrap(String toolName, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new RetryableError(ErrorCodes.UPSTREAM_UNAVAILABLE, "malformed MCP response for " + toolName);
        }
        if (body.hasNonNull("error")) {
            throw rpcError(toolName, body.get("error"));
        }
        JsonNode result = body.path("result");
        JsonNode payload = payload(result);
        if (payload == null) {
            throw new RetryableError(ErrorCodes.UPSTREAM_UNAVAILABLE, "MCP result without content for " + toolName);
        }
        if (result.path("isError").asBoolean(false)) {
            throw toolError(toolName, payload);
        }
        return payload;
    }

    private static JsonNode payload(JsonNode result) {
        JsonNode first = result.path("content").path(0);
        if ("json".equals(first.path("type").asText()) && first.has("json")) {
            return first.get("json");
        }
        if ("text".equals(first.path("type").asText()) && first.has("text")) {
            return first.get("text");
        }
        return null;
    }

    private static RuntimeException rpcError(String toolName, JsonNode error) {
        int code = error.path("code").asInt(0);
        String message = "MCP error " + code + " for " + toolName;
        return switch (code) {
            case INVALID_PARAMS, INVALID_REQUEST -> new NonRetryableError(ErrorCodes.VALIDATION_FAILED, message);
            case METHOD_NOT_FOUND -> new NonRetryableError(ErrorCodes.TOOL_NOT_FOUND, message);
            default -> new RetryableError(ErrorCodes.UPSTREAM_UNAVAILABLE, message);
        };
    }

    /** {@code isError:true}: the tool answered with {@code {error, status}}. */
    private static RuntimeException toolError(String toolName, JsonNode payload) {
        int status = payload.path("status").asInt(0);
        String error = payload.path("error").asText("");
        String message = toolName + " failed: status " + status + (error.isEmpty() ? "" : " " + error);
        if ("in_progress".equals(error) || (status == CONFLICT && error.isEmpty())) {
            return new ProviderInFlightException(message);
        }
        if (status == TOO_MANY_REQUESTS) {
            return new RetryableError(ErrorCodes.UPSTREAM_RATE_LIMITED, message);
        }
        if (status >= CLIENT_ERROR_MIN && status < SERVER_ERROR_MIN) {
            return new UpstreamClientError(status, payload, message);
        }
        return new RetryableError(ErrorCodes.UPSTREAM_UNAVAILABLE, message);
    }
}
