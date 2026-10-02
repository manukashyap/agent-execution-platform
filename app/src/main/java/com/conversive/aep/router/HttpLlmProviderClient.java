package com.conversive.aep.router;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.http.OutboundClient;
import com.conversive.aep.common.http.OutboundRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** OpenAI-shaped {@code POST /v1/chat/completions} over {@link OutboundClient}. */
@Component
public class HttpLlmProviderClient implements LlmProviderClient {

    private final OutboundClient outbound;
    private final ObjectMapper mapper;

    public HttpLlmProviderClient(OutboundClient outbound, ObjectMapper mapper) {
        this.outbound = outbound;
        this.mapper = mapper;
    }

    @Override
    public Reply complete(ProviderConfig provider, Call call) {
        OutboundRequest request = OutboundRequest.post(provider.completionsUri(), body(call), call.timeout(), call.mode());
        return parse(provider.name(), outbound.send(request).body());
    }

    ObjectNode body(Call call) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", call.model());
        ArrayNode messages = body.putArray("messages");
        call.messages().forEach(m -> messages.add(message(m)));
        if (!call.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (LlmToolSpec tool : call.tools()) {
                ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
                fn.put("name", tool.name()).put("description", tool.description());
                fn.set("parameters", tool.parameters() == null ? mapper.createObjectNode() : tool.parameters());
            }
        }
        return body;
    }

    private ObjectNode message(LlmMessage m) {
        ObjectNode node = mapper.createObjectNode().put("role", m.role()).put("content", m.content());
        if (m.toolCallId() != null) {
            node.put("tool_call_id", m.toolCallId());
        }
        if (!m.toolCalls().isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (LlmToolCall tc : m.toolCalls()) {
                calls.addObject().put("id", tc.id()).put("type", "function")
                        .putObject("function").put("name", tc.name()).put("arguments", tc.arguments());
            }
        }
        return node;
    }

    static Reply parse(String provider, JsonNode body) {
        JsonNode choice = body == null ? null : body.path("choices").path(0);
        if (choice == null || choice.isMissingNode() || !choice.path("message").isObject()) {
            throw new RetryableError(ErrorCodes.UPSTREAM_UNAVAILABLE, "malformed completion from " + provider);
        }
        JsonNode message = choice.path("message");
        JsonNode usage = body.path("usage");
        return new Reply(
                message.path("content").isTextual() ? message.path("content").asText() : null,
                toolCalls(message.path("tool_calls")),
                choice.path("finish_reason").asText(null),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0));
    }

    private static List<LlmToolCall> toolCalls(JsonNode node) {
        List<LlmToolCall> calls = new ArrayList<>();
        for (JsonNode tc : node) {
            JsonNode fn = tc.path("function");
            calls.add(new LlmToolCall(tc.path("id").asText(null), fn.path("name").asText(null),
                    fn.path("arguments").isTextual() ? fn.path("arguments").asText() : fn.path("arguments").toString()));
        }
        return calls;
    }
}
