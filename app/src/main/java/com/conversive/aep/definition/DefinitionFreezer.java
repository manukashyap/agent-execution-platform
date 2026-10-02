package com.conversive.aep.definition;

import com.conversive.aep.definition.model.Backoff;
import com.conversive.aep.definition.model.DefinitionCodec;
import com.conversive.aep.definition.model.FrozenDefinition;
import com.conversive.aep.definition.model.FrozenDefinition.Compensation;
import com.conversive.aep.definition.model.FrozenDefinition.ExecutionLimits;
import com.conversive.aep.definition.model.FrozenDefinition.ForEach;
import com.conversive.aep.definition.model.FrozenDefinition.FrozenNode;
import com.conversive.aep.definition.model.FrozenDefinition.RetryPolicy;
import com.conversive.aep.definition.model.Limits;
import com.conversive.aep.definition.model.NodeSpec;
import com.conversive.aep.definition.model.OnFailure;
import com.conversive.aep.definition.model.WorkflowDefinition;
import com.conversive.aep.tenancy.TenantLimits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns a validated definition into the {@link FrozenDefinition} an execution runs: explicit
 * dependencies, platform defaults, tenant ceilings for absent limits, and tool classification.
 * Done at execution start, from the stored spec, so a run never depends on later catalog edits.
 */
@Component
public class DefinitionFreezer {

    private static final long MILLIS_PER_SECOND = 1000;

    private final DefinitionProperties props;
    private final ToolCatalog catalog;

    public DefinitionFreezer(DefinitionProperties props, ToolCatalog catalog) {
        this.props = props;
        this.catalog = catalog;
    }

    /** @param def must have passed {@code DefinitionValidator} */
    public FrozenDefinition freeze(WorkflowDefinition def, String sha256, TenantLimits ceilings) {
        List<List<String>> deps = DefinitionCodec.resolveDependencies(def.nodes());
        List<FrozenNode> nodes = new ArrayList<>(def.nodes().size());
        for (int i = 0; i < def.nodes().size(); i++) {
            nodes.add(freezeNode(def.nodes().get(i), deps.get(i)));
        }
        int maxDuration = def.maxDurationS() == null ? ceilings.maxDurationS() : def.maxDurationS();
        int maxParallel = def.maxParallel() == null ? props.defaultMaxParallel() : def.maxParallel();
        return new FrozenDefinition(def.workflowId(), def.version(), sha256, nodes, maxDuration, maxParallel,
                limits(def.limits(), ceilings));
    }

    private ExecutionLimits limits(Limits limits, TenantLimits ceilings) {
        Limits given = limits == null ? new Limits(null, null, null) : limits;
        return new ExecutionLimits(
                given.maxCostUsd() == null ? ceilings.maxCostUsd() : given.maxCostUsd(),
                given.maxTokens() == null ? ceilings.maxTokens() : given.maxTokens(),
                given.maxNodeExecutions() == null ? ceilings.maxNodeExecutions() : given.maxNodeExecutions(),
                Math.min(ceilings.maxFanout(), props.maxWidth()));
    }

    private FrozenNode freezeNode(NodeSpec node, List<String> dependsOn) {
        NodeTraits traits = NodeTraits.of(node, catalog);
        RetryPolicy retry = retry(node.retry());
        int timeout = node.timeoutS() == null ? props.defaultTimeoutS() : node.timeoutS();
        int scheduleToClose = node.scheduleToCloseS() == null
                ? defaultScheduleToClose(timeout, retry) : node.scheduleToCloseS();
        return new FrozenNode(node.id(), node.type(), copyOrEmpty(node.config()), dependsOn, forEach(node.forEach()),
                retry, timeout, scheduleToClose, traits.sideEffecting(), compensation(node, traits), traits.pivot(),
                node.onFailure() == null ? OnFailure.FAIL_FAST : node.onFailure());
    }

    private RetryPolicy retry(NodeSpec.RetrySpec retry) {
        NodeSpec.RetrySpec given = retry == null ? new NodeSpec.RetrySpec(null, null, null) : retry;
        return new RetryPolicy(
                given.maxAttempts() == null ? props.defaultRetryAttempts() : given.maxAttempts(),
                given.initialIntervalMs() == null ? props.defaultInitialIntervalMs() : given.initialIntervalMs(),
                given.backoff() == null ? Backoff.EXPONENTIAL : given.backoff());
    }

    /** Every attempt's StartToClose plus all backoff waits plus the lease grace. */
    private int defaultScheduleToClose(int timeoutS, RetryPolicy retry) {
        double backoffMs = 0;
        double interval = retry.initialIntervalMs();
        for (int attempt = 1; attempt < retry.maxAttempts(); attempt++) {
            backoffMs += interval;
            interval *= retry.backoff().coefficient();
        }
        long backoffS = (long) Math.ceil(backoffMs / MILLIS_PER_SECOND);
        return Math.toIntExact(timeoutS * (long) retry.maxAttempts() + backoffS + props.leaseGraceS());
    }

    private ForEach forEach(NodeSpec.ForEachSpec forEach) {
        if (forEach == null) {
            return null;
        }
        int concurrency = forEach.maxConcurrency() == null
                ? props.defaultForEachConcurrency() : forEach.maxConcurrency();
        return new ForEach(forEach.items(), concurrency);
    }

    /** Declared compensation, else the tool's default compensating tool, else none. */
    private static Compensation compensation(NodeSpec node, NodeTraits traits) {
        NodeSpec.CompensateSpec declared = node.compensate();
        if (declared != null && declared.tool() != null) {
            return mcpCall(declared.tool(), declared.config());
        }
        if (declared != null) {
            return new Compensation(declared.type(), copyOrEmpty(declared.config()));
        }
        if (traits.tool() != null && traits.tool().compensation() != null) {
            return mcpCall(traits.tool().compensation(), null);
        }
        return null;
    }

    private static Compensation mcpCall(String tool, JsonNode config) {
        ObjectNode merged = (ObjectNode) copyOrEmpty(config);
        merged.put("tool", tool);
        return new Compensation("mcp", merged);
    }

    private static JsonNode copyOrEmpty(JsonNode config) {
        return config == null || !config.isObject() ? JsonNodeFactory.instance.objectNode() : config.deepCopy();
    }
}
