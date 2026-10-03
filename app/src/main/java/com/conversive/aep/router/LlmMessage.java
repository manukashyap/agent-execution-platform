package com.conversive.aep.router;

import java.util.List;
import java.util.Objects;

/** One chat message; {@code toolCallId} is set on {@code tool} messages, {@code toolCalls} on assistant turns that requested tools. */
public record LlmMessage(String role, String content, String toolCallId, List<LlmToolCall> toolCalls) {

    public LlmMessage {
        Objects.requireNonNull(role, "role");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static LlmMessage of(String role, String content) {
        return new LlmMessage(role, content, null, List.of());
    }

    public static LlmMessage user(String content) {
        return of("user", content);
    }
}
