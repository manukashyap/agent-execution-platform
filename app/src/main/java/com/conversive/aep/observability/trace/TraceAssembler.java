package com.conversive.aep.observability.trace;

import com.conversive.aep.observability.persistence.TraceRows.BudgetRow;
import com.conversive.aep.observability.persistence.TraceRows.ExecutionRow;
import com.conversive.aep.observability.persistence.TraceRows.LlmCallRow;
import com.conversive.aep.observability.persistence.TraceRows.NodeRunRow;
import com.conversive.aep.observability.persistence.TraceRows.SideEffectRow;
import com.conversive.aep.observability.persistence.TraceRows.ToolCallRow;
import com.conversive.aep.observability.trace.ExecutionTrace.AttemptTrace;
import com.conversive.aep.observability.trace.ExecutionTrace.Budget;
import com.conversive.aep.observability.trace.ExecutionTrace.LlmCallTrace;
import com.conversive.aep.observability.trace.ExecutionTrace.NodeTrace;
import com.conversive.aep.observability.trace.ExecutionTrace.SideEffectTrace;
import com.conversive.aep.observability.trace.ExecutionTrace.ToolCallTrace;
import com.conversive.aep.observability.trace.ExecutionTrace.Totals;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Pure: joins the per-table rows of one execution into its {@link ExecutionTrace}. */
public final class TraceAssembler {

    static final String FORWARD = "FORWARD";
    static final String UNKNOWN_TYPE = "unknown";

    private static final Comparator<NodeTrace> TIMELINE = Comparator
            .comparing(NodeTrace::startedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(NodeTrace::nodeId)
            .thenComparingInt(NodeTrace::callIndex)
            .thenComparing(NodeTrace::phase);

    private TraceAssembler() {
    }

    /** One node invocation; LLM calls carry no phase and always belong to FORWARD. */
    private record NodeKey(String nodeId, int callIndex, String phase) {
    }

    public static ExecutionTrace assemble(TraceData data) {
        Map<NodeKey, List<NodeRunRow>> runs = group(data.nodeRuns(),
                r -> new NodeKey(r.nodeId(), r.callIndex(), r.phase()));
        Map<NodeKey, List<LlmCallRow>> llm = group(data.llmCalls(),
                c -> new NodeKey(c.nodeId(), c.callIndex(), FORWARD));
        Map<NodeKey, List<ToolCallRow>> tools = group(data.toolCalls(),
                c -> new NodeKey(c.nodeId(), c.callIndex(), c.phase()));
        Map<NodeKey, List<SideEffectRow>> effects = group(data.sideEffects(),
                e -> new NodeKey(e.nodeId(), e.callIndex(), e.phase()));

        Set<NodeKey> keys = new LinkedHashSet<>();
        Stream.of(runs, llm, tools, effects).forEach(m -> keys.addAll(m.keySet()));
        List<NodeTrace> nodes = keys.stream()
                .map(k -> node(k, data.nodeTypes(), runs.getOrDefault(k, List.of()), llm.getOrDefault(k, List.of()),
                        tools.getOrDefault(k, List.of()), effects.getOrDefault(k, List.of())))
                .sorted(TIMELINE)
                .toList();

        ExecutionRow e = data.execution();
        return new ExecutionTrace(e.id(), e.workflowId(), e.version(), e.mode(), e.status(), e.errorCode(),
                e.startedAt(), e.endedAt(), durationMs(e.startedAt(), e.endedAt()),
                totals(nodes, data.llmCalls(), data.toolCalls(), data.budget()), nodes);
    }

    private static NodeTrace node(NodeKey key, Map<String, String> types, List<NodeRunRow> runs,
                                  List<LlmCallRow> llm, List<ToolCallRow> tools, List<SideEffectRow> effects) {
        List<NodeRunRow> attempts = runs.stream().sorted(Comparator.comparingInt(NodeRunRow::attempt)).toList();
        NodeRunRow last = attempts.isEmpty() ? null : attempts.getLast();
        Instant started = earliest(Stream.concat(attempts.stream().map(NodeRunRow::startedAt),
                Stream.concat(llm.stream().map(LlmCallRow::at), tools.stream().map(ToolCallRow::at))));
        Instant ended = last == null ? null : last.endedAt();
        return new NodeTrace(key.nodeId(), types.getOrDefault(key.nodeId(), UNKNOWN_TYPE), key.callIndex(),
                key.phase(), last == null ? null : last.status(), attempts.size(),
                Math.max(0, attempts.size() - 1), started, ended, durationMs(started, ended),
                last == null ? null : last.errorCode(),
                attempts.stream().map(TraceAssembler::attempt).toList(),
                llm.stream().map(TraceAssembler::llmCall).toList(),
                tools.stream().map(TraceAssembler::toolCall).toList(),
                effects.stream().map(TraceAssembler::sideEffect).toList());
    }

    private static Totals totals(List<NodeTrace> nodes, List<LlmCallRow> llm, List<ToolCallRow> tools,
                                 BudgetRow budget) {
        long prompt = llm.stream().mapToLong(LlmCallRow::promptTokens).sum();
        long completion = llm.stream().mapToLong(LlmCallRow::completionTokens).sum();
        BigDecimal cost = llm.stream().map(LlmCallRow::costUsd).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int fallbacks = (int) llm.stream().filter(c -> c.seq() > 0).count();
        return new Totals(nodes.size(), nodes.stream().mapToInt(NodeTrace::attempts).sum(),
                nodes.stream().mapToInt(NodeTrace::retries).sum(), llm.size(), fallbacks, prompt, completion,
                prompt + completion, cost, tools.size(), budget(budget));
    }

    private static Budget budget(BudgetRow row) {
        if (row == null) {
            return new Budget(null, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        return new Budget(row.limitUsd(), row.reservedUsd(), row.confirmedUsd(), row.cancelledReservations());
    }

    private static AttemptTrace attempt(NodeRunRow r) {
        return new AttemptTrace(r.attempt(), r.status(), r.startedAt(), r.endedAt(),
                durationMs(r.startedAt(), r.endedAt()), r.errorCode());
    }

    private static LlmCallTrace llmCall(LlmCallRow c) {
        return new LlmCallTrace(c.attempt(), c.turn(), c.seq(), c.provider(), c.model(), c.reason(), c.outcome(),
                c.errorCode(), c.promptTokens(), c.completionTokens(), c.costUsd(), c.latencyMs(), c.at());
    }

    private static ToolCallTrace toolCall(ToolCallRow c) {
        return new ToolCallTrace(c.attempt(), c.tool(), c.outcome(), c.errorCode(), c.latencyMs(), c.at());
    }

    private static SideEffectTrace sideEffect(SideEffectRow e) {
        return new SideEffectTrace(e.state(), e.idempotencyMode(), e.ownerAttempt(), e.externalRef(), e.updatedAt());
    }

    private static <T> Map<NodeKey, List<T>> group(List<T> rows, Function<T, NodeKey> key) {
        return rows.stream().collect(Collectors.groupingBy(key, Collectors.toUnmodifiableList()));
    }

    private static Instant earliest(Stream<Instant> instants) {
        return instants.filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
    }

    private static Long durationMs(Instant start, Instant end) {
        return start == null || end == null ? null : Duration.between(start, end).toMillis();
    }
}
