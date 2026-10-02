package com.conversive.aep.mocks.llm;

import com.conversive.aep.mocks.support.MockHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Deterministic OpenAI-shaped chat completion logic (no I/O, no randomness). */
@Component
public class LlmResponder {

    private static final List<String> LABELS = List.of("hot", "warm", "cold");

    private final ObjectMapper mapper;

    public LlmResponder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> respond(JsonNode request) {
        JsonNode messages = request.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            throw new MockHttpException(400, "messages_required");
        }
        String model = request.path("model").asText("mock-model");
        String input = joinedContent(messages);
        boolean hasToolResult = lastWithRole(messages, "tool") != null;
        JsonNode tools = request.path("tools");

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        String finishReason;
        String completionText;
        if (tools.isArray() && !tools.isEmpty() && !hasToolResult) {
            Map<String, Object> toolCall = toolCall(tools.get(0), lastWithRole(messages, "user"), input);
            message.put("content", null);
            message.put("tool_calls", List.of(toolCall));
            finishReason = "tool_calls";
            completionText = ((Map<?, ?>) toolCall.get("function")).get("arguments").toString();
        } else {
            completionText = finalContent(messages, input);
            message.put("content", completionText);
            finishReason = "stop";
        }
        return completion(model, input, message, finishReason, completionText);
    }

    private Map<String, Object> toolCall(JsonNode tool, JsonNode lastUser, String input) {
        String name = tool.path("function").path("name").asText();
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("arguments", toolArguments(lastUser));
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "call_" + hex(input + name));
        call.put("type", "function");
        call.put("function", function);
        return call;
    }

    private String toolArguments(JsonNode lastUser) {
        if (lastUser == null) {
            return "{}";
        }
        String content = text(lastUser);
        try {
            JsonNode parsed = mapper.readTree(content);
            return parsed != null && parsed.isObject() ? content : "{}";
        } catch (Exception e) {
            return "{}";
        }
    }

    private String finalContent(JsonNode messages, String input) {
        JsonNode toolMessage = lastWithRole(messages, "tool");
        if (toolMessage != null) {
            return "final: " + text(toolMessage);
        }
        return "{\"label\":\"" + LABELS.get(Math.floorMod(input.hashCode(), LABELS.size())) + "\"}";
    }

    private static Map<String, Object> completion(String model, String input, Map<String, Object> message,
                                                  String finishReason, String completionText) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finishReason);
        int promptTokens = tokens(input);
        int completionTokens = tokens(completionText);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", "chatcmpl-" + hex(input + completionText));
        out.put("model", model);
        out.put("choices", List.of(choice));
        out.put("usage", usage);
        return out;
    }

    private static int tokens(String s) {
        return (s.length() + 3) / 4;
    }

    private static String hex(String s) {
        return Integer.toHexString(s.hashCode());
    }

    private static JsonNode lastWithRole(JsonNode messages, String role) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (role.equals(messages.get(i).path("role").asText())) {
                return messages.get(i);
            }
        }
        return null;
    }

    private static String joinedContent(JsonNode messages) {
        StringBuilder sb = new StringBuilder();
        messages.forEach(m -> sb.append(text(m)));
        return sb.toString();
    }

    private static String text(JsonNode message) {
        JsonNode content = message.path("content");
        return content.isTextual() ? content.asText() : content.isMissingNode() || content.isNull() ? "" : content.toString();
    }
}
