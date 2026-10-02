package com.conversive.aep.nodes.http;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.http.OutboundClient;
import com.conversive.aep.common.http.OutboundRequest;
import com.conversive.aep.common.http.OutboundResponse;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.nodes.NodeExecutor;
import com.conversive.aep.nodes.NodeResult;
import com.conversive.aep.nodes.llm.PromptRenderer;
import com.conversive.aep.sideeffect.EffectCall;
import com.conversive.aep.sideeffect.EffectSpec;
import com.conversive.aep.sideeffect.SideEffectGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code http} node: config {@code {url, method?, headers?, body?}}; {@code {{path}}} placeholders in the url,
 * header values and string leaves of the body are rendered against the node input. Output
 * {@code {status, body}}. Error mapping is {@link OutboundClient}'s (429/5xx/timeout retryable, other 4xx not).
 * A side-effecting node goes through {@link SideEffectGuard}; the effect key is sent as {@code Idempotency-Key}.
 */
@Component
public class HttpExecutor implements NodeExecutor {

    public static final String TYPE = "http";

    private static final Duration HEADROOM = Duration.ofSeconds(1);
    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final OutboundClient client;
    private final SideEffectGuard guard;
    private final ObjectMapper mapper;

    public HttpExecutor(OutboundClient client, SideEffectGuard guard, ObjectMapper mapper) {
        this.client = client;
        this.guard = guard;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        JsonNode config = ctx.config() == null ? mapper.createObjectNode() : ctx.config();
        if (!ctx.sideEffecting()) {
            return NodeResult.of(call(request(config, ctx, timeout(ctx.startToClose()))));
        }
        EffectSpec spec = EffectSpec.of(ctx.tenantId(), ctx.executionId(), ctx.nodeId(), ctx.phase(),
                ctx.callIndex(), Math.max(1, ctx.attempt()), IdempotencyMode.NATIVE_KEY, ctx.startToClose());
        OutboundRequest request = request(config, ctx, spec.httpTimeout());
        return NodeResult.of(guard.run(spec, new HttpEffect(request)));
    }

    static Duration timeout(Duration startToClose) {
        if (startToClose == null) {
            return Duration.ofSeconds(30);
        }
        Duration t = startToClose.minus(HEADROOM);
        return t.compareTo(MIN_TIMEOUT) < 0 ? MIN_TIMEOUT : t;
    }

    private JsonNode call(OutboundRequest request) {
        OutboundResponse response = client.send(request);
        ObjectNode out = mapper.createObjectNode();
        out.put("status", response.status());
        out.set("body", response.body() == null ? mapper.nullNode() : response.body());
        return out;
    }

    private OutboundRequest request(JsonNode config, NodeContext ctx, Duration timeout) {
        String url = PromptRenderer.render(config.path("url").asText(""), ctx.input());
        if (url.isBlank()) {
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED, "http node " + ctx.nodeId() + " has no url");
        }
        String method = config.path("method").asText("GET").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED, "unsupported http method " + method);
        }
        JsonNode body = config.has("body") ? render(config.get("body"), ctx.input()) : null;
        return new OutboundRequest(method, uri(url), headers(config, ctx.input()), body, timeout, null, ctx.mode(),
                false);
    }

    private static URI uri(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED, "invalid url: " + e.getMessage(), e);
        }
    }

    private static Map<String, String> headers(JsonNode config, JsonNode input) {
        Map<String, String> headers = new LinkedHashMap<>();
        config.path("headers").properties().forEach(e -> {
            if (!OutboundClient.IDEMPOTENCY_KEY.equalsIgnoreCase(e.getKey())) {
                headers.put(e.getKey(), PromptRenderer.render(e.getValue().asText(), input));
            }
        });
        return headers;
    }

    private JsonNode render(JsonNode template, JsonNode input) {
        if (template.isTextual()) {
            return mapper.getNodeFactory().textNode(PromptRenderer.render(template.asText(), input));
        }
        if (template.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            template.forEach(item -> out.add(render(item, input)));
            return out;
        }
        if (template.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            template.properties().forEach(e -> out.set(e.getKey(), render(e.getValue(), input)));
            return out;
        }
        return template;
    }

    /** The guarded call: the ledger's effect key is the request's Idempotency-Key. */
    private final class HttpEffect implements EffectCall {

        private final OutboundRequest request;

        HttpEffect(OutboundRequest request) {
            this.request = request;
        }

        @Override
        public JsonNode invoke(String idempotencyKey) {
            return call(request.withIdempotencyKey(idempotencyKey));
        }

        @Override
        public Optional<String> externalRef(JsonNode response) {
            return EffectCall.super.externalRef(response == null ? null : response.path("body"));
        }
    }
}
