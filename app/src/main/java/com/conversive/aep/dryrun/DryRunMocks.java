package com.conversive.aep.dryrun;

import com.conversive.aep.common.Hashing;
import com.conversive.aep.definition.NodeTraits;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.nodes.NodeResult;
import com.conversive.aep.nodes.http.HttpExecutor;
import com.conversive.aep.nodes.llm.LlmExecutor;
import com.conversive.aep.nodes.mcp.McpToolExecutor;
import com.conversive.aep.tools.ArgsDigest;
import com.conversive.aep.tools.ToolCallAudit;
import com.conversive.aep.tools.ToolDefinition;
import com.conversive.aep.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Runs a node the dry-run policy mocks: no egress, no side-effect ledger, no budget, no LLM. The output comes from
 * the tool's {@code dryRunExample}, else a {@link SchemaSampler} instance of its output schema, else a fixed shape
 * per node type; every choice is seeded by {@code sha256(workflowId, defVersion, nodeId, sha256(input))}. Each call
 * is recorded in {@code tool_call_audit} so the preview can list it.
 */
@Component
public class DryRunMocks {

    private static final int SEED_PREFIX = 12;
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final ToolRegistry tools;
    private final ToolCallAudit audit;

    public DryRunMocks(ToolRegistry tools, ToolCallAudit audit) {
        this.tools = tools;
        this.audit = audit;
    }

    public NodeResult execute(NodeContext ctx) {
        MockedCall call = MockedCall.of(ctx);
        String seed = seed(ctx);
        JsonNode output = output(ctx, seed);
        audit.record(new ToolCallAudit.Entry(ctx.tenantId(), ctx.executionId(), ctx.nodeId(), ctx.callIndex(),
                ctx.phase(), Math.max(1, ctx.attempt()), call.auditName(), call.argsSha256(),
                ToolCallAudit.SUCCEEDED, null, 0, null));
        return new NodeResult(output, BigDecimal.ZERO, 0, Map.of("mocked", "true", "seed", seed));
    }

    public static String seed(NodeContext ctx) {
        JsonNode input = ctx.input() == null ? NullNode.getInstance() : ctx.input();
        return Hashing.sha256Hex(ctx.workflowId() + "|" + ctx.defVersion() + "|" + ctx.nodeId() + "|"
                + ArgsDigest.sha256(input));
    }

    private JsonNode output(NodeContext ctx, String seed) {
        return switch (ctx.nodeType()) {
            case McpToolExecutor.TYPE -> toolOutput(ctx.config(), seed);
            case HttpExecutor.TYPE -> httpOutput();
            case LlmExecutor.TYPE -> llmOutput(seed);
            default -> marker();
        };
    }

    private JsonNode toolOutput(JsonNode config, String seed) {
        Optional<ToolDefinition> tool = Optional.ofNullable(NodeTraits.toolName(config)).flatMap(tools::find);
        if (tool.isEmpty()) {
            return marker();
        }
        JsonNode example = tool.get().dryRunExample();
        if (example != null && !example.isMissingNode() && !example.isNull()) {
            return example.deepCopy();
        }
        return SchemaSampler.sample(tool.get().outputSchema(), seed);
    }

    private static JsonNode httpOutput() {
        ObjectNode out = JSON.objectNode();
        out.put("status", 200);
        out.set("body", marker());
        return out;
    }

    private static JsonNode llmOutput(String seed) {
        ObjectNode out = JSON.objectNode();
        out.put("text", "[dry-run mock completion " + seed.substring(0, SEED_PREFIX) + "]");
        out.put("provider", "mock");
        out.put("model", "mock");
        return out;
    }

    private static ObjectNode marker() {
        ObjectNode out = JSON.objectNode();
        out.put("dry_run", true);
        return out;
    }
}
