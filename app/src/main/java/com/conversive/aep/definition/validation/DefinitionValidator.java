package com.conversive.aep.definition.validation;

import static com.conversive.aep.definition.validation.ValidationCodes.*;

import com.conversive.aep.definition.DefinitionProperties;
import com.conversive.aep.definition.NodeTraits;
import com.conversive.aep.definition.ToolCatalog;
import com.conversive.aep.definition.model.DefinitionCodec;
import com.conversive.aep.definition.model.Limits;
import com.conversive.aep.definition.model.NodeSpec;
import com.conversive.aep.definition.model.WorkflowDefinition;
import com.conversive.aep.engine.workflow.condition.ValuePath;
import com.conversive.aep.tenancy.TenantLimits;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Validates a submitted definition against 06 §4.3; collects every finding instead of stopping at the first. */
@Component
public class DefinitionValidator {

    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    private final DefinitionProperties props;
    private final ToolCatalog catalog;
    private final NodeRules nodeRules;

    public DefinitionValidator(DefinitionProperties props, ToolCatalog catalog) {
        this.props = props;
        this.catalog = catalog;
        this.nodeRules = new NodeRules(props, catalog);
    }

    public ValidationReport validate(WorkflowDefinition def, TenantLimits ceilings) {
        Findings out = new Findings();
        checkHeader(def, out);
        checkLimits(def, ceilings, out);
        Map<String, NodeSpec> nodes = wellFormedNodes(def.nodes(), out);
        DefinitionGraph graph = buildGraph(def.nodes(), nodes, out);
        nodes.values().forEach(node -> nodeRules.check(node, graph, out));
        if (!graph.acyclic()) {
            out.error(CYCLE, null, "dependency cycle through " + graph.unordered());
            return out.report();
        }
        List<String> order = graph.order().orElseThrow();
        checkWidth(order, graph, nodes, out);
        Map<String, NodeTraits> traits = new LinkedHashMap<>();
        nodes.forEach((id, node) -> traits.put(id, NodeTraits.of(node, catalog)));
        SagaRules.check(order, graph, traits, out);
        return out.report();
    }

    private void checkHeader(WorkflowDefinition def, Findings out) {
        if (def.workflowId() == null || !NODE_ID.matcher(def.workflowId()).matches()) {
            out.error(MISSING_FIELD, null, "workflow_id is required and must match " + NODE_ID.pattern());
        }
        if (def.version() == null || def.version() < 1) {
            out.error(MISSING_FIELD, null, "version is required and must be >= 1");
        }
        if (def.nodes().isEmpty()) {
            out.error(MISSING_FIELD, null, "nodes must not be empty");
        }
        if (def.nodes().size() > props.maxNodes()) {
            out.error(TOO_MANY_NODES, null, def.nodes().size() + " nodes; at most " + props.maxNodes());
        }
        Integer maxParallel = def.maxParallel();
        if (maxParallel != null && (maxParallel < 1 || maxParallel > props.maxWidth())) {
            out.error(WIDTH_EXCEEDED, null, "max_parallel must be within [1, " + props.maxWidth() + "]");
        }
    }

    private void checkLimits(WorkflowDefinition def, TenantLimits ceilings, Findings out) {
        Limits limits = def.limits();
        if (limits != null) {
            checkCeiling("limits.max_cost_usd", limits.maxCostUsd(), ceilings.maxCostUsd(), out);
            checkCeiling("limits.max_tokens", toDecimal(limits.maxTokens()),
                    BigDecimal.valueOf(ceilings.maxTokens()), out);
            checkCeiling("limits.max_node_executions", toDecimal(limits.maxNodeExecutions()),
                    BigDecimal.valueOf(ceilings.maxNodeExecutions()), out);
        }
        Integer maxDuration = def.maxDurationS();
        if (maxDuration != null && maxDuration < 1) {
            out.error(INVALID_LIMIT, null, "max_duration_s must be >= 1");
        } else {
            checkCeiling("max_duration_s", toDecimal(maxDuration), BigDecimal.valueOf(ceilings.maxDurationS()), out);
        }
    }

    private static void checkCeiling(String field, BigDecimal value, BigDecimal ceiling, Findings out) {
        if (value == null) {
            return;
        }
        if (value.signum() < 0) {
            out.error(INVALID_LIMIT, null, field + " must be >= 0");
        } else if (value.compareTo(ceiling) > 0) {
            out.error(LIMIT_ABOVE_CEILING, null, field + " " + value.toPlainString()
                    + " exceeds the tenant ceiling " + ceiling.toPlainString());
        }
    }

    private static BigDecimal toDecimal(Number value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    /** First occurrence of each valid id, in list order; malformed and duplicate nodes are reported. */
    private static Map<String, NodeSpec> wellFormedNodes(List<NodeSpec> nodes, Findings out) {
        Map<String, NodeSpec> byId = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            NodeSpec node = nodes.get(i);
            if (node == null || node.id() == null) {
                out.error(MISSING_FIELD, null, "nodes[" + i + "].id is required");
            } else if (!NODE_ID.matcher(node.id()).matches() || ValuePath.INPUT.equals(node.id())) {
                out.error(INVALID_NODE_ID, node.id(), "node id must match " + NODE_ID.pattern()
                        + " and must not be '" + ValuePath.INPUT + "'");
            } else if (byId.putIfAbsent(node.id(), node) != null) {
                out.error(DUPLICATE_NODE_ID, node.id(), "duplicate node id");
            }
        }
        return byId;
    }

    private static DefinitionGraph buildGraph(List<NodeSpec> all, Map<String, NodeSpec> nodes, Findings out) {
        List<List<String>> resolved = DefinitionCodec.resolveDependencies(all);
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            NodeSpec node = all.get(i);
            if (node != null && nodes.get(node.id()) == node) {
                deps.put(node.id(), resolved.get(i));
            }
        }
        deps.forEach((id, ds) -> ds.stream().filter(dep -> !deps.containsKey(dep)).forEach(dep ->
                out.error(MISSING_DEPENDENCY, id, "depends on unknown node '" + dep + "'")));
        return new DefinitionGraph(deps);
    }

    /** Static width: per topological level, the sum of each node's parallelism (forEach concurrency or 1). */
    private void checkWidth(List<String> order, DefinitionGraph graph, Map<String, NodeSpec> nodes, Findings out) {
        Map<String, Integer> levels = graph.levels();
        Map<Integer, Integer> width = new LinkedHashMap<>();
        for (String id : order) {
            NodeSpec.ForEachSpec forEach = nodes.get(id).forEach();
            int parallelism = forEach == null ? 1
                    : forEach.maxConcurrency() == null ? props.defaultForEachConcurrency() : forEach.maxConcurrency();
            width.merge(levels.get(id), Math.max(parallelism, 1), Integer::sum);
        }
        width.forEach((level, total) -> {
            if (total > props.maxWidth()) {
                out.error(WIDTH_EXCEEDED, null, "static parallel width " + total + " at depth " + level
                        + " exceeds " + props.maxWidth());
            }
        });
    }
}
