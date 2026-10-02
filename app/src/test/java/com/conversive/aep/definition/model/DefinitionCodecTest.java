package com.conversive.aep.definition.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.definition.model.FrozenDefinition.Compensation;
import com.conversive.aep.definition.model.FrozenDefinition.ExecutionLimits;
import com.conversive.aep.definition.model.FrozenDefinition.ForEach;
import com.conversive.aep.definition.model.FrozenDefinition.FrozenNode;
import com.conversive.aep.definition.model.FrozenDefinition.RetryPolicy;
import com.conversive.aep.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import io.temporal.common.converter.DefaultDataConverter;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefinitionCodecTest {

    @Test
    void parsesThePdfExampleVerbatim() {
        WorkflowDefinition def = DefinitionCodec.parse(Fixtures.pdfExample());

        assertThat(def.workflowId()).isEqualTo("lead_enrichment");
        assertThat(def.version()).isEqualTo(1);
        assertThat(def.nodes()).extracting(NodeSpec::id)
                .containsExactly("fetch_leads", "classify_leads", "update_crm", "send_message");
        assertThat(def.nodes().get(0).type()).isEqualTo("http");
        assertThat(def.nodes().get(0).config().path("method").asText()).isEqualTo("GET");
        assertThat(def.nodes()).allSatisfy(n -> assertThat(n.dependsOn()).isNull());
    }

    @Test
    void parsesSnakeCaseOptionalFields() {
        JsonNode spec = DefinitionCodec.readTree("""
                {"workflow_id":"w","version":2,"max_duration_s":60,"max_parallel":8,
                 "limits":{"max_cost_usd":0.5,"max_tokens":1000,"max_node_executions":10},
                 "nodes":[{"id":"a","type":"http","config":{"url":"http://x"},"depends_on":[],
                   "for_each":{"items":"$.input.items","max_concurrency":4},
                   "retry":{"max_attempts":3,"initial_interval_ms":200,"backoff":"exponential"},
                   "timeout_s":5,"schedule_to_close_s":40,"side_effecting":true,"pivot":false,
                   "on_failure":"continue","compensate":{"tool":"payments.refund","config":{"x":1}},
                   "unknown_field":"ignored"}]}
                """);

        WorkflowDefinition def = DefinitionCodec.parse(spec);
        NodeSpec a = def.nodes().get(0);

        assertThat(def.maxDurationS()).isEqualTo(60);
        assertThat(def.maxParallel()).isEqualTo(8);
        assertThat(def.limits()).isEqualTo(new Limits(new BigDecimal("0.5"), 1000L, 10));
        assertThat(a.dependsOn()).isEmpty();
        assertThat(a.forEach()).isEqualTo(new NodeSpec.ForEachSpec("$.input.items", 4));
        assertThat(a.retry()).isEqualTo(new NodeSpec.RetrySpec(3, 200L, Backoff.EXPONENTIAL));
        assertThat(a.timeoutS()).isEqualTo(5);
        assertThat(a.scheduleToCloseS()).isEqualTo(40);
        assertThat(a.sideEffecting()).isTrue();
        assertThat(a.onFailure()).isEqualTo(OnFailure.CONTINUE);
        assertThat(a.compensate().tool()).isEqualTo("payments.refund");
    }

    @Test
    void failWorkflowIsAnAliasOfFailFast() {
        assertThat(OnFailure.fromJson("FAIL_WORKFLOW")).isEqualTo(OnFailure.FAIL_FAST);
    }

    @Test
    void rejectsNonObjectAndBadTypes() {
        assertThatThrownBy(() -> DefinitionCodec.parse(DefinitionCodec.readTree("[]")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DefinitionCodec.parse(DefinitionCodec.readTree(
                "{\"workflow_id\":\"w\",\"version\":\"one\",\"nodes\":[]}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void implicitDependencyIsThePreviousNodeAndEmptyListIsARoot() {
        List<NodeSpec> nodes = List.of(
                NodeSpec.of("a", "http", null),
                NodeSpec.of("b", "http", null),
                new NodeSpec("c", "http", null, List.of(), null, null, null, null, null, null, null, null),
                new NodeSpec("d", "http", null, List.of("a", "c"), null, null, null, null, null, null, null, null));

        assertThat(DefinitionCodec.resolveDependencies(nodes))
                .containsExactly(List.of(), List.of("a"), List.of(), List.of("a", "c"));
    }

    @Test
    void sha256IgnoresKeyOrderAndWhitespace() {
        JsonNode one = DefinitionCodec.readTree("{\"a\":1,\"b\":{\"y\":[1,2],\"x\":true}}");
        JsonNode two = DefinitionCodec.readTree("{ \"b\" : { \"x\" : true, \"y\" : [1, 2] }, \"a\" : 1 }");
        JsonNode three = DefinitionCodec.readTree("{\"a\":1,\"b\":{\"y\":[2,1],\"x\":true}}");

        assertThat(DefinitionCodec.sha256(one)).isEqualTo(DefinitionCodec.sha256(two)).hasSize(64);
        assertThat(DefinitionCodec.sha256(one)).isNotEqualTo(DefinitionCodec.sha256(three));
    }

    @Test
    void frozenDefinitionRoundTripsThroughTheTemporalDataConverter() {
        FrozenDefinition frozen = new FrozenDefinition("w", 3, "abc",
                List.of(new FrozenNode("a", "mcp", DefinitionCodec.readTree("{\"tool\":\"payments.charge\"}"),
                        List.of(), new ForEach("$.input.items", 4), new RetryPolicy(3, 1000, Backoff.EXPONENTIAL),
                        10, 50, true, new Compensation("mcp", DefinitionCodec.readTree("{\"tool\":\"payments.refund\"}")),
                        false, OnFailure.FAIL_FAST)),
                600, 16, new ExecutionLimits(new BigDecimal("1.25"), 1000, 500, 100));

        var converter = DefaultDataConverter.newDefaultInstance();
        var payloads = converter.toPayloads(frozen).orElseThrow();
        FrozenDefinition back = converter.fromPayloads(0, java.util.Optional.of(payloads),
                FrozenDefinition.class, FrozenDefinition.class);

        assertThat(back).isEqualTo(frozen);
    }
}
