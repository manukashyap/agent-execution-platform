package com.conversive.aep.observability.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.observability.persistence.TraceRows.ExecutionRow;
import com.conversive.aep.observability.persistence.TraceRows.LlmCallRow;
import com.conversive.aep.observability.persistence.TraceRows.NodeRunRow;
import com.conversive.aep.observability.persistence.TraceRows.SideEffectRow;
import com.conversive.aep.observability.persistence.TraceRows.ToolCallRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TraceAssemblerTest {

    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");

    @Test
    void anExecutionWithNoRowsHasEmptyTotalsAndNoDuration() {
        ExecutionTrace trace = TraceAssembler.assemble(data(List.of(), List.of(), List.of(), List.of(), null));

        assertThat(trace.nodes()).isEmpty();
        assertThat(trace.durationMs()).isNull();
        assertThat(trace.totals().costUsd()).isEqualByComparingTo("0");
        assertThat(trace.totals().budget().limitUsd()).isNull();
    }

    @Test
    void compensationIsItsOwnNodeAndRowsWithoutANodeRunStillAppear() {
        List<NodeRunRow> runs = List.of(
                new NodeRunRow("charge", 0, "FORWARD", 1, "SUCCEEDED", null, T0, T0.plusMillis(100)),
                new NodeRunRow("charge", 0, "COMPENSATE", 1, "SUCCEEDED", null, T0.plusMillis(500),
                        T0.plusMillis(700)));
        List<ToolCallRow> tools = List.of(
                new ToolCallRow("refund_probe", 2, "FORWARD", 1, "crm.get", "FAILED", "UPSTREAM_TIMEOUT", 30,
                        T0.plusMillis(300)));
        List<SideEffectRow> effects = List.of(
                new SideEffectRow("charge", 0, "COMPENSATE", "COMMITTED", "NATIVE_KEY", 1, "re_1", T0));

        ExecutionTrace trace = TraceAssembler.assemble(data(runs, List.of(), tools, effects, null));

        assertThat(trace.nodes()).extracting(n -> n.nodeId() + "/" + n.callIndex() + "/" + n.phase())
                .containsExactly("charge/0/FORWARD", "refund_probe/2/FORWARD", "charge/0/COMPENSATE");
        ExecutionTrace.NodeTrace probe = trace.nodes().get(1);
        assertThat(probe.type()).isEqualTo(TraceAssembler.UNKNOWN_TYPE);
        assertThat(probe.attempts()).isZero();
        assertThat(probe.status()).isNull();
        assertThat(probe.startedAt()).isEqualTo(T0.plusMillis(300));
        assertThat(trace.nodes().get(2).sideEffects()).singleElement()
                .extracting(ExecutionTrace.SideEffectTrace::externalRef).isEqualTo("re_1");
    }

    @Test
    void llmCallsJoinTheForwardInvocationOfTheirCallIndex() {
        List<NodeRunRow> runs = List.of(
                new NodeRunRow("classify", 0, "FORWARD", 1, "SUCCEEDED", null, T0, T0.plusSeconds(1)),
                new NodeRunRow("classify", 1, "FORWARD", 1, "SUCCEEDED", null, T0, T0.plusSeconds(2)));
        List<LlmCallRow> llm = List.of(llmCall(1, 0), llmCall(1, 1));

        ExecutionTrace trace = TraceAssembler.assemble(data(runs, llm, List.of(), List.of(), null));

        assertThat(trace.nodes().get(0).llmCalls()).isEmpty();
        assertThat(trace.nodes().get(1).llmCalls()).hasSize(2);
        assertThat(trace.totals().llmFallbacks()).isEqualTo(1);
        assertThat(trace.totals().totalTokens()).isEqualTo(30);
        assertThat(trace.totals().costUsd()).isEqualByComparingTo("0.002");
    }

    private static LlmCallRow llmCall(int callIndex, int seq) {
        return new LlmCallRow("classify", callIndex, 1, 0, seq, "llm-b", "model-b", "best_score", 10, 5,
                new BigDecimal("0.001"), 50, "SUCCEEDED", null, T0);
    }

    private static TraceData data(List<NodeRunRow> runs, List<LlmCallRow> llm, List<ToolCallRow> tools,
                                  List<SideEffectRow> effects, Instant ended) {
        ExecutionRow execution = new ExecutionRow(UUID.randomUUID(), "wf", 1, "LIVE", "RUNNING", null, T0, ended);
        return new TraceData(execution, Map.of("charge", "mcp", "classify", "llm"), runs, llm, tools, effects, null);
    }
}
