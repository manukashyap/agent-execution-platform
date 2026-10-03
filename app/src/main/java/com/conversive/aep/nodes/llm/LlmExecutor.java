package com.conversive.aep.nodes.llm;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.Priority;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.nodes.NodeExecutor;
import com.conversive.aep.nodes.NodeResult;
import com.conversive.aep.router.LlmMessage;
import com.conversive.aep.router.LlmRequest;
import com.conversive.aep.router.LlmResponse;
import com.conversive.aep.router.LlmRouter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code llm} node: config {@code {prompt | messages, system?, capability?, priority?, model?}}. Prompts are
 * rendered against the node input, routed through {@link LlmRouter} (which records one {@code llm_call}
 * row per provider call), and the reply is returned as {@code {text, json?, provider, model}}.
 */
@Component
public class LlmExecutor implements NodeExecutor {

    public static final String TYPE = "llm";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TIMEOUT_HEADROOM = Duration.ofSeconds(1);
    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);

    private final LlmRouter router;
    private final ObjectMapper mapper;
    private final LlmToolRound tools;

    /** Without a tool round: {@code tools} in the config is rejected. */
    public LlmExecutor(LlmRouter router, ObjectMapper mapper) {
        this(router, mapper, null);
    }

    @Autowired
    public LlmExecutor(LlmRouter router, ObjectMapper mapper, LlmToolRound tools) {
        this.router = router;
        this.mapper = mapper;
        this.tools = tools;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        JsonNode config = ctx.config() == null ? mapper.createObjectNode() : ctx.config();
        LlmRequest request = new LlmRequest(ctx.tenantId(), ctx.executionId(), ctx.nodeId(), ctx.callIndex(),
                Math.max(1, ctx.attempt()), 0, priority(config, ctx), capabilities(config), messages(config, ctx.input()),
                List.of(), text(config, "model"), timeout(ctx), ctx.mode());
        if (config.path("tools").isArray() && !config.path("tools").isEmpty()) {
            return toolRound(ctx, config, request);
        }
        return toResult(router.route(request));
    }

    /**
     * The AI tool round (T4.5, {@link LlmToolRound}): config {@code tools: [READ_ONLY toolName…]} and
     * {@code maxToolCalls} (default 1, cap 3 → {@code TOOL_CALL_LIMIT}). Output adds {@code tool_results:
     * [{tool, result}]}; cost and tokens are summed over every turn.
     */
    protected NodeResult toolRound(NodeContext ctx, JsonNode config, LlmRequest firstTurn) {
        if (tools == null) {
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED, "llm node 'tools' is not supported here");
        }
        LlmToolRound.Outcome outcome = tools.run(ctx, config, firstTurn);
        NodeResult last = toResult(outcome.finalResponse());
        ObjectNode output = ((ObjectNode) last.output()).set("tool_results", outcome.toolResults());
        Map<String, String> meta = new LinkedHashMap<>(last.meta());
        meta.put("turns", Integer.toString(outcome.turns()));
        meta.put("tool_calls", Integer.toString(outcome.toolResults().size()));
        return new NodeResult(output, outcome.costUsd(), outcome.tokens(), meta);
    }

    NodeResult toResult(LlmResponse response) {
        ObjectNode output = mapper.createObjectNode();
        output.put("text", response.content());
        parseJson(response.content()).ifPresent(json -> output.set("json", json));
        output.put("provider", response.provider());
        output.put("model", response.model());
        return new NodeResult(output, response.costUsd(), response.totalTokens(),
                Map.of("provider", response.provider(), "model", response.model(), "reason", response.reason()));
    }

    private Optional<JsonNode> parseJson(String content) {
        String trimmed = content == null ? "" : content.strip();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readTree(trimmed));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static List<LlmMessage> messages(JsonNode config, JsonNode input) {
        List<LlmMessage> messages = new ArrayList<>();
        String system = text(config, "system");
        if (system != null) {
            messages.add(LlmMessage.of("system", PromptRenderer.render(system, input)));
        }
        if (config.path("messages").isArray()) {
            for (JsonNode m : config.path("messages")) {
                String content = PromptRenderer.render(m.path("content").asText(""), input);
                messages.add(LlmMessage.of(m.path("role").asText("user"), content));
            }
        } else if (text(config, "prompt") != null) {
            messages.add(LlmMessage.user(PromptRenderer.render(text(config, "prompt"), input)));
        }
        if (messages.stream().allMatch(m -> "system".equals(m.role()))) {
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED, "llm node needs 'prompt' or 'messages'");
        }
        return messages;
    }

    private static Priority priority(JsonNode config, NodeContext ctx) {
        String value = text(config, "priority");
        if (value == null) {
            return ctx.priority() == null ? Priority.NORMAL : ctx.priority();
        }
        try {
            return Priority.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED, "unknown llm priority: " + value);
        }
    }

    private static Set<String> capabilities(JsonNode config) {
        JsonNode node = config.path("capability");
        Set<String> caps = new LinkedHashSet<>();
        if (node.isTextual()) {
            caps.add(node.asText());
        } else if (node.isArray()) {
            node.forEach(c -> caps.add(c.asText()));
        }
        return caps;
    }

    private static Duration timeout(NodeContext ctx) {
        if (ctx.startToClose() == null) {
            return DEFAULT_TIMEOUT;
        }
        Duration budget = ctx.startToClose().minus(TIMEOUT_HEADROOM);
        return budget.compareTo(MIN_TIMEOUT) < 0 ? MIN_TIMEOUT : budget;
    }

    private static String text(JsonNode config, String field) {
        JsonNode node = config.path(field);
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
