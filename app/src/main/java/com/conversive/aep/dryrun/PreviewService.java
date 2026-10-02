package com.conversive.aep.dryrun;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.DefinitionFreezer;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.definition.StoredDefinition;
import com.conversive.aep.definition.model.DefinitionCodec;
import com.conversive.aep.definition.model.FrozenDefinition;
import com.conversive.aep.definition.model.FrozenDefinition.FrozenNode;
import com.conversive.aep.dryrun.DryRunPreview.CallPreview;
import com.conversive.aep.dryrun.DryRunPreview.MockedCallView;
import com.conversive.aep.dryrun.DryRunPreview.NodePreview;
import com.conversive.aep.engine.persistence.NodeOutputRepository;
import com.conversive.aep.engine.persistence.NodeOutputRepository.StoredOutput;
import com.conversive.aep.execution.persistence.ExecutionRecord;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.persistence.NodeRunRecord;
import com.conversive.aep.tools.ToolCallAudit;
import com.conversive.aep.tools.ToolCallAudit.Entry;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** T6.2: assembles {@link DryRunPreview} from one consistent snapshot of the execution's rows. */
@Service
public class PreviewService {

    private final ExecutionRepository executions;
    private final NodeOutputRepository outputs;
    private final ToolCallAudit audit;
    private final DefinitionService definitions;
    private final DefinitionFreezer freezer;

    public PreviewService(ExecutionRepository executions, NodeOutputRepository outputs, ToolCallAudit audit,
                          DefinitionService definitions, DefinitionFreezer freezer) {
        this.executions = executions;
        this.outputs = outputs;
        this.audit = audit;
        this.definitions = definitions;
        this.freezer = freezer;
    }

    /**
     * @throws NonRetryableError {@code NOT_FOUND} when the execution does not exist for this tenant;
     *                           {@code CONFLICT} when it is not a dry run
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DryRunPreview preview(TenantId tenant, ExecutionId id) {
        ExecutionRecord execution = executions.findById(tenant, id).orElseThrow(
                () -> new NonRetryableError(ErrorCodes.NOT_FOUND, "execution " + id.value() + " not found"));
        if (execution.mode() != ExecutionMode.DRY_RUN) {
            throw new NonRetryableError(ErrorCodes.CONFLICT,
                    "execution " + id.value() + " is a " + execution.mode() + " run; only dry runs have a preview");
        }
        FrozenDefinition definition = freeze(tenant, execution);
        List<NodeRunRecord> runs = executions.findNodeRuns(tenant, id);
        List<MockedCallView> mocked = mockedCalls(definition, audit.findByExecution(tenant, id));
        List<NodePreview> nodes = nodes(definition, runs, mocked, outputs(tenant, id, definition));
        JsonNode options = execution.options() == null ? null : execution.options().get("dryRun");
        return new DryRunPreview(execution.workflowId(), execution.defVersion(), execution.status(), options,
                nodes, mocked, CompensationPlan.render(definition, runs));
    }

    private FrozenDefinition freeze(TenantId tenant, ExecutionRecord execution) {
        StoredDefinition stored = definitions.get(tenant, execution.workflowId(), execution.defVersion());
        return freezer.freeze(DefinitionCodec.parse(stored.spec()), stored.sha256(), definitions.ceilings(tenant));
    }

    private Map<String, JsonNode> outputs(TenantId tenant, ExecutionId id, FrozenDefinition definition) {
        Set<String> ids = definition.nodes().stream().map(FrozenNode::id).collect(Collectors.toSet());
        Map<String, JsonNode> byCall = new HashMap<>();
        for (StoredOutput output : outputs.find(tenant, id, ids)) {
            byCall.put(key(output.nodeId(), output.callIndex()), output.payload());
        }
        return byCall;
    }

    /** One view per mocked (node, call, phase), however many attempts it took, in definition order. */
    private static List<MockedCallView> mockedCalls(FrozenDefinition definition, List<Entry> entries) {
        Map<String, Integer> order = definitionOrder(definition);
        Map<String, MockedCallView> unique = new LinkedHashMap<>();
        for (Entry entry : entries) {
            Optional<MockedCall> call = MockedCall.fromAuditName(entry.toolName());
            if (call.isEmpty()) {
                continue;
            }
            String phase = entry.phase() == null ? Phase.FORWARD.name() : entry.phase().name();
            unique.putIfAbsent(key(entry.nodeId(), entry.callIndex()) + "#" + phase, new MockedCallView(
                    entry.nodeId(), entry.callIndex(), phase, call.get().nodeType(), call.get().target(),
                    entry.argsSha256()));
        }
        return unique.values().stream()
                .sorted(Comparator.<MockedCallView>comparingInt(v -> order.getOrDefault(v.nodeId(), Integer.MAX_VALUE))
                        .thenComparingInt(MockedCallView::callIndex)
                        .thenComparing(MockedCallView::phase))
                .toList();
    }

    private static List<NodePreview> nodes(FrozenDefinition definition, List<NodeRunRecord> runs,
                                           List<MockedCallView> mocked, Map<String, JsonNode> outputs) {
        Map<String, TreeMap<Integer, NodeRunRecord>> latest = latestForwardAttempts(runs);
        Set<String> mockedForward = mocked.stream().filter(v -> Phase.FORWARD.name().equals(v.phase()))
                .map(v -> key(v.nodeId(), v.callIndex())).collect(Collectors.toSet());
        List<NodePreview> nodes = new ArrayList<>(definition.nodes().size());
        for (FrozenNode node : definition.nodes()) {
            List<CallPreview> calls = new ArrayList<>();
            latest.getOrDefault(node.id(), new TreeMap<>()).forEach((callIndex, run) -> {
                String key = key(node.id(), callIndex);
                calls.add(new CallPreview(callIndex, run.status(), mockedForward.contains(key), outputs.get(key)));
            });
            nodes.add(new NodePreview(node.id(), node.type(), calls));
        }
        return nodes;
    }

    private static Map<String, TreeMap<Integer, NodeRunRecord>> latestForwardAttempts(List<NodeRunRecord> runs) {
        Map<String, TreeMap<Integer, NodeRunRecord>> latest = new HashMap<>();
        for (NodeRunRecord run : runs) {
            if (!Phase.FORWARD.name().equals(run.phase())) {
                continue;
            }
            latest.computeIfAbsent(run.nodeId(), k -> new TreeMap<>())
                    .merge(run.callIndex(), run, (a, b) -> b.attempt() > a.attempt() ? b : a);
        }
        return latest;
    }

    private static Map<String, Integer> definitionOrder(FrozenDefinition definition) {
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < definition.nodes().size(); i++) {
            order.put(definition.nodes().get(i).id(), i);
        }
        return order;
    }

    private static String key(String nodeId, int callIndex) {
        return nodeId + "#" + callIndex;
    }
}
