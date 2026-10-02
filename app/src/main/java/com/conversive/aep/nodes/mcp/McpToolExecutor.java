package com.conversive.aep.nodes.mcp;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.nodes.NodeExecutor;
import com.conversive.aep.nodes.NodeResult;
import com.conversive.aep.tools.JsonTemplate;
import com.conversive.aep.tools.ToolGateway;
import com.conversive.aep.tools.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code mcp} node: config {@code {tool, args}}; {@code args} strings of the form {@code {{path}}} are filled from the
 * node input (typed, non-executable), then the call goes through {@link ToolGateway} (grants, validation, ledger).
 */
@Component
public class McpToolExecutor implements NodeExecutor {

    public static final String TYPE = "mcp";

    private final ToolGateway gateway;

    public McpToolExecutor(ToolGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        JsonNode config = ctx.config();
        String tool = config == null ? "" : config.path("tool").asText("");
        if (tool.isBlank()) {
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED, "mcp node " + ctx.nodeId() + " needs 'tool'");
        }
        JsonNode args = JsonTemplate.render(config.path("args"), ctx.input());
        JsonNode result = gateway.invoke(new ToolInvocation(ctx.tenantId(), ctx.executionId(), ctx.nodeId(),
                ctx.callIndex(), Math.max(1, ctx.attempt()), ctx.phase(), ctx.mode(), tool, args, ctx.startToClose()));
        return new NodeResult(result, BigDecimal.ZERO, 0, Map.of("tool", tool));
    }
}
