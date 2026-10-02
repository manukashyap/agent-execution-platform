package com.conversive.aep.dryrun;

import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.Reversibility;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.definition.NodeTraits;
import com.conversive.aep.nodes.DryRunOptions;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.nodes.http.HttpExecutor;
import com.conversive.aep.nodes.llm.LlmExecutor;
import com.conversive.aep.nodes.mcp.McpToolExecutor;
import com.conversive.aep.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * The 06 §4.10 policy table for non-{@code LIVE} executions:
 * <ul>
 *   <li>{@code llm}: live unless {@code dryRun.mockLlm}</li>
 *   <li>{@code mcp} calling a {@code READ_ONLY} tool: live unless {@code dryRun.allowReadOnly=false}</li>
 *   <li>{@code http} <em>declared</em> {@code side_effecting:false}: live unless {@code allowReadOnly=false}</li>
 *   <li>everything else (side-effecting nodes, compensations, unknown tools, unclassified http): mocked</li>
 * </ul>
 * The frozen definition keeps only a boolean, so "declared false" for http is read from the stored spec.
 */
@Component
public class DryRunPolicy {

    private final ToolRegistry tools;
    private final DefinitionService definitions;

    public DryRunPolicy(ToolRegistry tools, DefinitionService definitions) {
        this.tools = tools;
        this.definitions = definitions;
    }

    public boolean runsLive(NodeContext ctx) {
        if (ctx.mode() == ExecutionMode.LIVE) {
            return true;
        }
        if (ctx.phase() == Phase.COMPENSATE) {
            return false;
        }
        DryRunOptions options = ctx.dryRun() == null ? DryRunOptions.defaults() : ctx.dryRun();
        return switch (ctx.nodeType()) {
            case LlmExecutor.TYPE -> !options.mockLlm();
            case McpToolExecutor.TYPE -> options.allowReadOnly() && readOnlyTool(ctx.config());
            case HttpExecutor.TYPE -> options.allowReadOnly() && !ctx.sideEffecting() && declaredReadOnly(ctx);
            default -> false;
        };
    }

    private boolean readOnlyTool(JsonNode config) {
        String name = NodeTraits.toolName(config);
        return name != null && tools.find(name)
                .map(tool -> tool.reversibility() == Reversibility.READ_ONLY)
                .orElse(false);
    }

    private boolean declaredReadOnly(NodeContext ctx) {
        JsonNode spec = definitions.get(ctx.tenantId(), ctx.workflowId(), ctx.defVersion()).spec();
        for (JsonNode node : spec.path("nodes")) {
            if (ctx.nodeId().equals(node.path("id").asText())) {
                JsonNode flag = node.has("side_effecting") ? node.get("side_effecting") : node.get("sideEffecting");
                return flag != null && flag.isBoolean() && !flag.booleanValue();
            }
        }
        return false;
    }
}
