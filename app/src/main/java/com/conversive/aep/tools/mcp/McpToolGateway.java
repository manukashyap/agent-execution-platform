package com.conversive.aep.tools.mcp;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.observability.AepMetrics;
import com.conversive.aep.sideeffect.EffectCall;
import com.conversive.aep.sideeffect.EffectSpec;
import com.conversive.aep.sideeffect.ProviderInFlightException;
import com.conversive.aep.sideeffect.SideEffectGuard;
import com.conversive.aep.sideeffect.TimingContract;
import com.conversive.aep.tools.ArgsDigest;
import com.conversive.aep.tools.JsonTemplate;
import com.conversive.aep.tools.ToolAccess;
import com.conversive.aep.tools.ToolArgsValidator;
import com.conversive.aep.tools.ToolCallAudit;
import com.conversive.aep.tools.ToolCredentialProvider;
import com.conversive.aep.tools.ToolDefinition;
import com.conversive.aep.tools.ToolGateway;
import com.conversive.aep.tools.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single path to tools: grant check, schema validation, ledger guard for side-effecting tools, MCP call, and one
 * {@code tool_call_audit} row per attempt (written whatever the outcome).
 */
@Component
public class McpToolGateway implements ToolGateway {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolGateway.class);

    private final ToolAccess access;
    private final ToolArgsValidator validator;
    private final SideEffectGuard guard;
    private final McpClient mcp;
    private final ToolCredentialProvider credentials;
    private final ToolCallAudit audit;
    private final AepMetrics metrics;

    public McpToolGateway(ToolAccess access, ToolArgsValidator validator, SideEffectGuard guard, McpClient mcp,
                          ToolCredentialProvider credentials, ToolCallAudit audit, AepMetrics metrics) {
        this.access = access;
        this.validator = validator;
        this.guard = guard;
        this.mcp = mcp;
        this.credentials = credentials;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Override
    public JsonNode invoke(ToolInvocation inv) {
        long started = System.nanoTime();
        String effectKey = null;
        String meteredTool = null;
        try {
            ToolDefinition tool = access.authorize(inv.tenantId(), inv.toolName());
            meteredTool = tool.name();
            validator.validate(tool, inv.args());
            Duration stc = inv.startToCloseOr(tool.timeout());
            if (!tool.sideEffecting()) {
                JsonNode result = direct(tool, inv, stc);
                record(inv, meteredTool, ToolCallAudit.SUCCEEDED, null, started, null);
                return result;
            }
            EffectSpec spec = EffectSpec.of(inv.tenantId(), inv.executionId(), inv.nodeId(), inv.phase(),
                    inv.callIndex(), inv.attempt(), tool.idempotency(), stc);
            effectKey = spec.key().value();
            JsonNode result = guard.run(spec, new GuardedCall(tool, inv, spec.httpTimeout()));
            record(inv, meteredTool, ToolCallAudit.SUCCEEDED, null, started, effectKey);
            return result;
        } catch (RuntimeException e) {
            record(inv, meteredTool, outcome(e), code(e), started, effectKey);
            throw e;
        }
    }

    @Override
    public Optional<JsonNode> lookup(ToolInvocation inv) {
        ToolDefinition tool = access.authorize(inv.tenantId(), inv.toolName());
        validator.validate(tool, inv.args());
        Duration stc = inv.startToCloseOr(tool.timeout());
        return new GuardedCall(tool, inv, TimingContract.httpTimeout(stc)).lookup();
    }

    private JsonNode direct(ToolDefinition tool, ToolInvocation inv, Duration stc) {
        try {
            return call(tool.name(), inv, inv.args(), null, TimingContract.httpTimeout(stc));
        } catch (ProviderInFlightException e) {
            throw new RetryableError(ErrorCodes.EFFECT_IN_PROGRESS, e.getMessage());
        }
    }

    private JsonNode call(String toolName, ToolInvocation inv, JsonNode args, String key, Duration timeout) {
        return mcp.callTool(toolName, args, key, credentials.credential(inv.tenantId(), toolName), timeout, inv.mode());
    }

    /** {@code meteredTool} is null until the grant check passes, so a metric label never carries an unvetted name. */
    private void record(ToolInvocation inv, String meteredTool, String outcome, String errorCode, long startedNanos,
                        String effectKey) {
        Duration latency = Duration.ofNanos(System.nanoTime() - startedNanos);
        long latencyMs = latency.toMillis();
        metrics.toolCall(inv.tenantId(), meteredTool, outcome, errorCode, latency);
        try {
            audit.record(new ToolCallAudit.Entry(inv.tenantId(), inv.executionId(), inv.nodeId(), inv.callIndex(),
                    inv.phase(), inv.attempt(), inv.toolName(), ArgsDigest.sha256(inv.args()), outcome, errorCode,
                    latencyMs, effectKey));
        } catch (RuntimeException e) {
            // The audit must never mask the tool's own result or error.
            LOG.error("tool_call_audit write failed for tool={} execution={} node={} attempt={}",
                    inv.toolName(), inv.executionId(), inv.nodeId(), inv.attempt(), e);
        }
    }

    private static String outcome(RuntimeException e) {
        boolean inProgress = e instanceof RetryableError r && ErrorCodes.EFFECT_IN_PROGRESS.equals(r.code());
        return inProgress ? ToolCallAudit.IN_PROGRESS : ToolCallAudit.FAILED;
    }

    private static String code(RuntimeException e) {
        if (e instanceof NonRetryableError n) {
            return n.code();
        }
        if (e instanceof RetryableError r) {
            return r.code();
        }
        return ErrorCodes.INTERNAL;
    }

    /** The ledger-guarded MCP call; {@link #lookup()} serves {@code LOOKUP} tools such as crm.upsert. */
    private final class GuardedCall implements EffectCall {

        private final ToolDefinition tool;
        private final ToolInvocation inv;
        private final Duration timeout;

        GuardedCall(ToolDefinition tool, ToolInvocation inv, Duration timeout) {
            this.tool = tool;
            this.inv = inv;
            this.timeout = timeout;
        }

        @Override
        public JsonNode invoke(String idempotencyKey) {
            return call(tool.name(), inv, inv.args(), idempotencyKey, timeout);
        }

        @Override
        public Optional<JsonNode> lookup() {
            JsonNode spec = tool.lookup();
            if (tool.idempotency() != IdempotencyMode.LOOKUP || spec == null || !spec.path("tool").isTextual()) {
                return Optional.empty();
            }
            ObjectNode scope = JsonNodeFactory.instance.objectNode();
            scope.set("args", inv.args());
            JsonNode args = JsonTemplate.render(spec.path("args"), scope);
            return firstMatch(call(spec.get("tool").asText(), inv, args, null, timeout));
        }

        /** "Found" = the first array field of the lookup result is non-empty; its first element is the effect. */
        private Optional<JsonNode> firstMatch(JsonNode result) {
            if (result == null || !result.isObject()) {
                return Optional.empty();
            }
            for (JsonNode value : result) {
                if (value.isArray()) {
                    return value.isEmpty() ? Optional.empty() : Optional.of(value.get(0));
                }
            }
            return Optional.empty();
        }
    }
}
