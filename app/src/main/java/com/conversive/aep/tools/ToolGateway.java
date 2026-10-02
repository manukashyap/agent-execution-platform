package com.conversive.aep.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The only way nodes call tools (06 §4.8): grant/scope check → JSON-schema validation → {@code SideEffectGuard}
 * for non-READ_ONLY tools → MCP {@code tools/call} → one {@code tool_call_audit} row per attempt.
 * Errors follow the platform taxonomy ({@code RetryableError} / {@code NonRetryableError(code)}).
 */
public interface ToolGateway {

    JsonNode invoke(ToolInvocation invocation);
}
