package com.conversive.aep.engine.activity;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.engine.activity.NodeTask.UpstreamRef;
import com.conversive.aep.engine.persistence.NodeOutputRepository;
import com.conversive.aep.engine.persistence.NodeOutputRepository.StoredOutput;
import com.conversive.aep.engine.workflow.condition.ValuePath;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Builds a node's input: {@code {"input": <execution input>, "<ancestorId>": <output>..., "item", "index"}}.
 * A fan-out ancestor contributes the array of its item outputs; {@code item}/{@code index} are set for
 * {@code for_each} calls only.
 */
@Component
public class NodeInputAssembler {

    private final ExecutionRepository executions;
    private final NodeOutputRepository outputs;
    private final ObjectMapper mapper;

    public NodeInputAssembler(ExecutionRepository executions, NodeOutputRepository outputs, ObjectMapper mapper) {
        this.executions = executions;
        this.outputs = outputs;
        this.mapper = mapper;
    }

    public JsonNode assemble(NodeTask task) {
        ObjectNode input = mapper.createObjectNode();
        input.set(ValuePath.INPUT, executionInput(task.tenantId(), task.executionId()));
        Set<String> fanOut = new LinkedHashSet<>();
        Set<String> ids = new LinkedHashSet<>();
        for (UpstreamRef ref : task.upstream()) {
            ids.add(ref.nodeId());
            if (ref.fanOut()) {
                fanOut.add(ref.nodeId());
            }
        }
        for (StoredOutput out : outputs.find(task.tenantId(), task.executionId(), ids)) {
            if (fanOut.contains(out.nodeId())) {
                ArrayNode items = input.has(out.nodeId()) ? (ArrayNode) input.get(out.nodeId())
                        : input.putArray(out.nodeId());
                items.add(out.payload());
            } else {
                input.set(out.nodeId(), out.payload());
            }
        }
        fanOut.stream().filter(id -> !input.has(id)).forEach(input::putArray);
        if (task.itemsPath() != null) {
            input.set("item", item(task, input));
            input.put("index", task.callIndex());
        }
        return input;
    }

    /** The stored output of one node, for the interpreter ({@code null} when nothing is stored). */
    public JsonNode output(TenantId tenantId, ExecutionId executionId, String nodeId, boolean fanOut) {
        List<StoredOutput> rows = outputs.find(tenantId, executionId, List.of(nodeId));
        if (fanOut) {
            ArrayNode items = mapper.createArrayNode();
            rows.forEach(row -> items.add(row.payload()));
            return items;
        }
        return rows.isEmpty() ? null : rows.get(0).payload();
    }

    private JsonNode item(NodeTask task, ObjectNode input) {
        ValuePath path = ValuePath.parse(task.itemsPath());
        JsonNode items = path.resolve(input.path(path.root()));
        if (!items.isArray() || task.callIndex() >= items.size()) {
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED,
                    "for_each item " + task.callIndex() + " not found at " + task.itemsPath());
        }
        return items.get(task.callIndex());
    }

    private JsonNode executionInput(TenantId tenantId, ExecutionId executionId) {
        return executions.findById(tenantId, executionId)
                .map(row -> row.input() == null ? (JsonNode) mapper.createObjectNode() : row.input())
                .orElseThrow(() -> new NonRetryableError(ErrorCodes.NOT_FOUND,
                        "execution " + executionId + " not found"));
    }
}
