package com.conversive.aep.dryrun;

import com.conversive.aep.definition.NodeTraits;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.nodes.http.HttpExecutor;
import com.conversive.aep.nodes.llm.LlmExecutor;
import com.conversive.aep.nodes.llm.PromptRenderer;
import com.conversive.aep.nodes.mcp.McpToolExecutor;
import com.conversive.aep.tools.ArgsDigest;
import com.conversive.aep.tools.JsonTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Optional;

/**
 * What a mocked node would have called: its node type, a target (tool name, {@code METHOD url} or LLM model) and
 * the arguments it would have sent. It is recorded in {@code tool_call_audit} under the tool name
 * {@code mock:<type>:<target>} with only the SHA-256 of the arguments.
 */
public record MockedCall(String nodeType, String target, JsonNode args) {

    static final String AUDIT_PREFIX = "mock:";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    public static MockedCall of(NodeContext ctx) {
        JsonNode config = ctx.config() == null ? JSON.objectNode() : ctx.config();
        JsonNode input = ctx.input() == null ? NullNode.getInstance() : ctx.input();
        return switch (ctx.nodeType()) {
            case McpToolExecutor.TYPE -> new MockedCall(ctx.nodeType(), orUnknown(NodeTraits.toolName(config)),
                    JsonTemplate.render(config.path("args"), input));
            case HttpExecutor.TYPE -> http(config, input);
            case LlmExecutor.TYPE -> new MockedCall(ctx.nodeType(), config.path("model").asText("auto"),
                    configAndInput(config, input));
            default -> new MockedCall(ctx.nodeType(), ctx.nodeType(), configAndInput(config, input));
        };
    }

    public String argsSha256() {
        return ArgsDigest.sha256(args);
    }

    public String auditName() {
        return AUDIT_PREFIX + nodeType + ":" + target;
    }

    /** The {@code (nodeType, target)} of an audit row written by {@link #auditName()}; empty for real tool calls. */
    public static Optional<MockedCall> fromAuditName(String toolName) {
        if (toolName == null || !toolName.startsWith(AUDIT_PREFIX)) {
            return Optional.empty();
        }
        String rest = toolName.substring(AUDIT_PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        return Optional.of(new MockedCall(rest.substring(0, colon), rest.substring(colon + 1), null));
    }

    private static MockedCall http(JsonNode config, JsonNode input) {
        String method = config.path("method").asText("GET").toUpperCase(Locale.ROOT);
        String url = PromptRenderer.render(config.path("url").asText(""), input);
        ObjectNode args = JSON.objectNode();
        args.put("method", method);
        args.put("url", url);
        args.set("body", config.has("body") ? JsonTemplate.render(config.get("body"), input) : NullNode.getInstance());
        return new MockedCall(HttpExecutor.TYPE, method + " " + url, args);
    }

    private static ObjectNode configAndInput(JsonNode config, JsonNode input) {
        ObjectNode args = JSON.objectNode();
        args.set("config", config);
        args.set("input", input);
        return args;
    }

    private static String orUnknown(String tool) {
        return tool == null ? "unknown" : tool;
    }
}
