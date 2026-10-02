package com.conversive.aep.definition.validation;

import static com.conversive.aep.definition.validation.ValidationCodes.*;

import com.conversive.aep.definition.DefinitionProperties;
import com.conversive.aep.definition.NodeTraits;
import com.conversive.aep.definition.ToolCatalog;
import com.conversive.aep.definition.ToolInfo;
import com.conversive.aep.definition.model.NodeSpec;
import com.conversive.aep.engine.workflow.condition.Condition;
import com.conversive.aep.engine.workflow.condition.ConditionSyntaxException;
import com.conversive.aep.engine.workflow.condition.ValuePath;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Checks that only need one node (plus the graph for condition branches). */
final class NodeRules {

    static final Set<String> NODE_TYPES = Set.of("http", "llm", "mcp", "condition");

    private final DefinitionProperties props;
    private final ToolCatalog catalog;

    NodeRules(DefinitionProperties props, ToolCatalog catalog) {
        this.props = props;
        this.catalog = catalog;
    }

    void check(NodeSpec node, DefinitionGraph graph, Findings out) {
        checkTypeAndConfig(node, graph, out);
        checkForEach(node, out);
        checkTimeouts(node, out);
        checkRetry(node, out);
        checkCompensate(node, out);
    }

    private void checkTypeAndConfig(NodeSpec node, DefinitionGraph graph, Findings out) {
        if (node.type() == null || !NODE_TYPES.contains(node.type())) {
            out.error(UNKNOWN_NODE_TYPE, node.id(), "unknown node type '" + node.type() + "'; expected one of "
                    + NODE_TYPES);
            return;
        }
        JsonNode config = node.config();
        switch (node.type()) {
            case "http" -> requireText(node, config, "url", out);
            case "mcp" -> checkMcp(node, out);
            case "llm" -> checkLlmTools(node, config, out);
            default -> checkCondition(node, graph, out);
        }
    }

    private void requireText(NodeSpec node, JsonNode config, String field, Findings out) {
        if (config == null || !config.path(field).isTextual() || config.path(field).asText().isBlank()) {
            out.error(INVALID_CONFIG, node.id(), node.type() + " node needs config." + field);
        }
    }

    private void checkMcp(NodeSpec node, Findings out) {
        String tool = NodeTraits.toolName(node.config());
        if (tool == null) {
            out.error(INVALID_CONFIG, node.id(), "mcp node needs config.tool");
        } else if (catalog.find(tool).isEmpty()) {
            out.error(UNKNOWN_TOOL, node.id(), "unknown tool '" + tool + "'");
        }
    }

    private void checkLlmTools(NodeSpec node, JsonNode config, Findings out) {
        JsonNode tools = config == null ? null : config.get("tools");
        if (tools == null || tools.isNull()) {
            return;
        }
        if (!tools.isArray()) {
            out.error(INVALID_CONFIG, node.id(), "llm config.tools must be an array of tool names");
            return;
        }
        for (JsonNode name : tools) {
            Optional<ToolInfo> tool = catalog.find(name.isTextual() ? name.asText() : null);
            if (tool.isEmpty()) {
                out.error(UNKNOWN_TOOL, node.id(), "unknown tool '" + name.asText() + "'");
            } else if (!tool.get().readOnly()) {
                out.error(AI_TOOL_NOT_READ_ONLY, node.id(),
                        "LLM-selected tools must be READ_ONLY; '" + tool.get().name() + "' is "
                                + tool.get().reversibility());
            }
        }
    }

    private void checkCondition(NodeSpec node, DefinitionGraph graph, Findings out) {
        Condition condition;
        try {
            condition = Condition.parse(node.config());
        } catch (ConditionSyntaxException e) {
            out.error(CONDITION_INVALID, node.id(), e.getMessage());
            return;
        }
        String root = condition.left().root();
        if (!ValuePath.INPUT.equals(root) && !graph.contains(root)) {
            out.error(CONDITION_INVALID, node.id(), "condition refers to unknown node '" + root + "'");
        }
        checkBranches(node.id(), condition.thenNodes(), graph, out);
        checkBranches(node.id(), condition.elseNodes(), graph, out);
    }

    private static void checkBranches(String conditionId, List<String> branch, DefinitionGraph graph,
                                      Findings out) {
        for (String target : branch) {
            if (!graph.contains(target) || !graph.dependencies(target).contains(conditionId)) {
                out.error(CONDITION_BRANCH_INVALID, conditionId,
                        "branch target '" + target + "' must be a node that depends on '" + conditionId + "'");
            }
        }
    }

    private void checkForEach(NodeSpec node, Findings out) {
        NodeSpec.ForEachSpec forEach = node.forEach();
        if (forEach == null) {
            return;
        }
        if ("condition".equals(node.type())) {
            out.error(FOR_EACH_INVALID, node.id(), "condition nodes cannot fan out");
        }
        try {
            ValuePath.parse(forEach.items());
        } catch (ConditionSyntaxException e) {
            out.error(FOR_EACH_INVALID, node.id(), "for_each.items: " + e.getMessage());
        }
        Integer concurrency = forEach.maxConcurrency();
        if (concurrency != null && concurrency < 1) {
            out.error(FOR_EACH_INVALID, node.id(), "for_each.max_concurrency must be >= 1");
        } else if (concurrency != null && concurrency > props.maxWidth()) {
            out.error(FANOUT_CONCURRENCY_EXCEEDED, node.id(),
                    "for_each.max_concurrency " + concurrency + " exceeds " + props.maxWidth());
        }
    }

    private void checkTimeouts(NodeSpec node, Findings out) {
        Integer timeout = node.timeoutS();
        if (timeout != null && (timeout < props.minTimeoutS() || timeout > props.maxTimeoutS())) {
            out.error(TIMEOUT_OUT_OF_RANGE, node.id(), "timeout_s must be within [" + props.minTimeoutS() + ", "
                    + props.maxTimeoutS() + "]");
        }
        Integer scheduleToClose = node.scheduleToCloseS();
        int startToClose = timeout == null ? props.defaultTimeoutS() : timeout;
        if (scheduleToClose != null && scheduleToClose < startToClose + props.leaseGraceS()) {
            out.error(SCHEDULE_TO_CLOSE_TOO_SHORT, node.id(), "schedule_to_close_s must be >= timeout_s ("
                    + startToClose + ") + lease grace (" + props.leaseGraceS() + ")");
        }
    }

    private void checkRetry(NodeSpec node, Findings out) {
        NodeSpec.RetrySpec retry = node.retry();
        if (retry == null) {
            return;
        }
        Integer attempts = retry.maxAttempts();
        if (attempts != null && (attempts < 1 || attempts > props.maxRetryAttempts())) {
            out.error(RETRY_OUT_OF_RANGE, node.id(), "retry.max_attempts must be within [1, "
                    + props.maxRetryAttempts() + "]");
        }
        if (retry.initialIntervalMs() != null && retry.initialIntervalMs() < 0) {
            out.error(RETRY_OUT_OF_RANGE, node.id(), "retry.initial_interval_ms must be >= 0");
        }
    }

    private void checkCompensate(NodeSpec node, Findings out) {
        NodeSpec.CompensateSpec compensate = node.compensate();
        if (compensate == null) {
            return;
        }
        if (!NodeTraits.of(node, catalog).sideEffecting()) {
            out.error(COMPENSATE_WITHOUT_SIDE_EFFECT, node.id(),
                    "compensate is only allowed on side-effecting nodes");
        }
        if (compensate.tool() != null) {
            if (catalog.find(compensate.tool()).isEmpty()) {
                out.error(UNKNOWN_TOOL, node.id(), "unknown compensation tool '" + compensate.tool() + "'");
            }
        } else if (compensate.type() == null || !NODE_TYPES.contains(compensate.type())
                || "condition".equals(compensate.type())) {
            out.error(INVALID_CONFIG, node.id(), "compensate needs a tool or a type of http, llm or mcp");
        }
    }
}
