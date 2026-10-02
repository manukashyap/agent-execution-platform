package com.conversive.aep.router;

/** A tool call requested by the model; {@code arguments} is the raw JSON string the provider returned. */
public record LlmToolCall(String id, String name, String arguments) {
}
