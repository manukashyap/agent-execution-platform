package com.conversive.aep.dryrun;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.Reversibility;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.nodes.DryRunOptions;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.tools.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Builders shared by the dry-run unit tests. */
final class DryRunFixtures {

    static final TenantId TENANT = TenantId.of("t_dry");
    static final ExecutionId EXECUTION = new ExecutionId(UUID.fromString("00000000-0000-0000-0000-000000000006"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DryRunFixtures() {
    }

    static NodeContext ctx(String type, String config, ExecutionMode mode, Phase phase, boolean sideEffecting,
                           DryRunOptions options) {
        return new NodeContext(TENANT, EXECUTION, "wf", 1, "n1", type, 0, 1, phase, mode, sideEffecting,
                json(config), json("{\"input\":{\"x\":1}}"), Duration.ofSeconds(10), Priority.NORMAL, options);
    }

    static NodeContext dryRun(String type, String config, boolean sideEffecting, DryRunOptions options) {
        return ctx(type, config, ExecutionMode.DRY_RUN, Phase.FORWARD, sideEffecting, options);
    }

    static ToolDefinition tool(String name, Reversibility reversibility, String outputSchema, String example) {
        return new ToolDefinition(name, 1, name, json("{\"type\":\"object\"}"),
                outputSchema == null ? null : json(outputSchema), List.of(), Duration.ofSeconds(5), null,
                reversibility, IdempotencyMode.NONE, null, null, example == null ? null : json(example));
    }

    static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
