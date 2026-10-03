package com.conversive.aep.mocks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LlmRoutesIT extends MockIT {

    private static final Map<String, Object> TOOL = Map.of("type", "function", "function",
            Map.of("name", "crm.upsert", "description", "d", "parameters", Map.of("type", "object")));

    private static String path(String provider) {
        return "/llm/" + provider + "/v1/chat/completions";
    }

    private static Map<String, Object> request(List<Map<String, Object>> messages, boolean withTools) {
        return withTools
                ? Map.of("model", "m", "messages", messages, "tools", List.of(TOOL))
                : Map.of("model", "m", "messages", messages);
    }

    @ParameterizedTest
    @ValueSource(strings = {"llm-a", "llm-b", "vllm"})
    void classificationWithoutToolsIsDeterministicAndShaped(String provider) throws Exception {
        var messages = List.<Map<String, Object>>of(Map.of("role", "user", "content", "score this lead"));

        Reply first = post(path(provider), request(messages, false));
        Reply second = post(path(provider), request(messages, false));

        JsonNode choice = first.body().get("choices").get(0);
        assertThat(first.status()).isEqualTo(200);
        assertThat(choice.get("finish_reason").asText()).isEqualTo("stop");
        assertThat(choice.get("message").get("role").asText()).isEqualTo("assistant");
        assertThat(choice.get("message").get("content").asText()).matches("\\{\"label\":\"(hot|warm|cold)\"}");
        assertThat(first.body().get("usage").get("total_tokens").asInt())
                .isEqualTo(first.body().get("usage").get("prompt_tokens").asInt()
                        + first.body().get("usage").get("completion_tokens").asInt());
        assertThat(second.body().get("choices").get(0).get("message").get("content"))
                .isEqualTo(choice.get("message").get("content"));
        assertThat(first.body().get("usage").get("prompt_tokens").asInt()).isEqualTo(4); // ceil(15/4)
    }

    @Test
    void toolCallThenFinalTurn() throws Exception {
        String args = "{\"external_ref\":\"l-1\",\"name\":\"Ada\",\"email\":\"a@x.test\"}";
        var turn1 = List.<Map<String, Object>>of(Map.of("role", "user", "content", args));

        JsonNode choice1 = post(path("llm-a"), request(turn1, true)).body().get("choices").get(0);
        JsonNode call = choice1.get("message").get("tool_calls").get(0);

        assertThat(choice1.get("finish_reason").asText()).isEqualTo("tool_calls");
        assertThat(call.get("type").asText()).isEqualTo("function");
        assertThat(call.get("function").get("name").asText()).isEqualTo("crm.upsert");
        assertThat(call.get("function").get("arguments").asText()).isEqualTo(args);

        var turn2 = List.of(turn1.get(0),
                Map.<String, Object>of("role", "assistant", "content", ""),
                Map.<String, Object>of("role", "tool", "tool_call_id", call.get("id").asText(),
                        "content", "{\"contact_id\":\"ct_1\"}"));
        JsonNode choice2 = post(path("llm-a"), request(turn2, true)).body().get("choices").get(0);

        assertThat(choice2.get("finish_reason").asText()).isEqualTo("stop");
        assertThat(choice2.get("message").get("content").asText()).isEqualTo("final: {\"contact_id\":\"ct_1\"}");
    }

    @Test
    void toolCallArgumentsDefaultToEmptyObjectWhenUserContentIsNotJsonObject() throws Exception {
        var messages = List.<Map<String, Object>>of(Map.of("role", "user", "content", "plain text"));

        JsonNode call = post(path("vllm"), request(messages, true)).body()
                .get("choices").get(0).get("message").get("tool_calls").get(0);

        assertThat(call.get("function").get("arguments").asText()).isEqualTo("{}");
    }

    @Test
    void defaultLatenciesPerProviderAndOverride() throws Exception {
        var messages = List.<Map<String, Object>>of(Map.of("role", "user", "content", "x"));
        long vllm = timed(() -> post(path("vllm"), request(messages, false)));
        long llmA = timed(() -> post(path("llm-a"), request(messages, false)));

        assertThat(vllm).isGreaterThanOrEqualTo(100L);
        assertThat(llmA).isGreaterThanOrEqualTo(200L);

        post("/admin/latency", Map.of("route", "llm-a", "ms", 0));
        assertThat(timed(() -> post(path("llm-a"), request(messages, false)))).isLessThan(150L);
    }

    @Test
    void unknownProviderIs404() throws Exception {
        assertThat(post(path("nope"), Map.of("messages", List.of())).status()).isEqualTo(404);
    }

    private static long timed(ThrowingRunnable r) throws Exception {
        long start = System.nanoTime();
        r.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
