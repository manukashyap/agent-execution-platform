package com.conversive.aep.router;

import com.conversive.aep.common.ExecutionMode;
import java.time.Duration;
import java.util.List;

/**
 * Transport to one provider. Throws {@code RetryableError} for timeouts, 5xx, 429 and malformed replies
 * (the router falls back) and {@code NonRetryableError} for other 4xx (the router stops).
 */
public interface LlmProviderClient {

    Reply complete(ProviderConfig provider, Call call);

    record Call(String model, List<LlmMessage> messages, List<LlmToolSpec> tools, Duration timeout, ExecutionMode mode) {

        public Call {
            messages = List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    record Reply(String content, List<LlmToolCall> toolCalls, String finishReason, int promptTokens, int completionTokens) {

        public Reply {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public long totalTokens() {
            return (long) promptTokens + completionTokens;
        }
    }
}
