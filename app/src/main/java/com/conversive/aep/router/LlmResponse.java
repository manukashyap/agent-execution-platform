package com.conversive.aep.router;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of a successful route.
 *
 * @param reason   router decision reason of the call that succeeded ({@link RouteReasons})
 * @param costUsd  cost of the successful call (failed calls are not billed)
 * @param attempts every provider call made by this route, in order (the last one succeeded)
 */
public record LlmResponse(
        String provider,
        String model,
        String content,
        List<LlmToolCall> toolCalls,
        String finishReason,
        int promptTokens,
        int completionTokens,
        BigDecimal costUsd,
        String reason,
        List<ProviderAttempt> attempts) {

    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    public long totalTokens() {
        return (long) promptTokens + completionTokens;
    }

    public record ProviderAttempt(String provider, String reason, String outcome, String errorCode, long latencyMs) {
    }
}
