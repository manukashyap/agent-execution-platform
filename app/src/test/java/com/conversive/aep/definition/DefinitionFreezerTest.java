package com.conversive.aep.definition;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.model.Backoff;
import com.conversive.aep.definition.model.DefinitionCodec;
import com.conversive.aep.definition.model.FrozenDefinition;
import com.conversive.aep.definition.model.FrozenDefinition.FrozenNode;
import com.conversive.aep.definition.model.OnFailure;
import com.conversive.aep.support.Fixtures;
import com.conversive.aep.tenancy.TenantLimits;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DefinitionFreezerTest {

    private static final TenantLimits CEILINGS = TenantLimits.defaults(new TenantId("t_dev"));

    private final DefinitionFreezer freezer =
            new DefinitionFreezer(DefinitionProperties.defaults(), new InMemoryToolCatalog());

    @Test
    void pdfExampleFreezesWithDefaultsAndImplicitSequentialOrder() {
        FrozenDefinition frozen = freezer.freeze(Fixtures.pdfExampleDefinition(), "abc", CEILINGS);

        assertThat(frozen.workflowId()).isEqualTo("lead_enrichment");
        assertThat(frozen.version()).isEqualTo(1);
        assertThat(frozen.sha256()).isEqualTo("abc");
        assertThat(frozen.maxDurationS()).isEqualTo(3600);
        assertThat(frozen.maxParallel()).isEqualTo(16);
        assertThat(frozen.limits()).isEqualTo(
                new FrozenDefinition.ExecutionLimits(new BigDecimal("5"), 200_000, 500, 100));
        assertThat(frozen.nodes()).extracting(FrozenNode::dependsOn).containsExactly(
                java.util.List.of(), java.util.List.of("fetch_leads"), java.util.List.of("classify_leads"),
                java.util.List.of("update_crm"));

        FrozenNode fetch = frozen.node("fetch_leads").orElseThrow();
        assertThat(fetch.timeoutS()).isEqualTo(30);
        assertThat(fetch.retry()).isEqualTo(new FrozenDefinition.RetryPolicy(3, 1000, Backoff.EXPONENTIAL));
        assertThat(fetch.scheduleToCloseS()).isGreaterThanOrEqualTo(30 + 5);
        assertThat(fetch.onFailure()).isEqualTo(OnFailure.FAIL_FAST);
        assertThat(fetch.sideEffecting()).isFalse();

        FrozenNode crm = frozen.node("update_crm").orElseThrow();
        assertThat(crm.sideEffecting()).isTrue();
        assertThat(crm.pivot()).isFalse();
        assertThat(frozen.node("send_message").orElseThrow().pivot()).isTrue();
    }

    @Test
    void appliesDeclaredValuesAndToolDefaultCompensation() {
        String json = "{\"workflow_id\":\"w\",\"version\":2,\"max_duration_s\":60,\"max_parallel\":4,"
                + "\"limits\":{\"max_cost_usd\":1.5,\"max_tokens\":1000},\"nodes\":["
                + "{\"id\":\"pay\",\"type\":\"mcp\",\"config\":{\"tool\":\"payments.charge\"},\"timeout_s\":10,"
                + "\"retry\":{\"max_attempts\":2,\"initial_interval_ms\":500,\"backoff\":\"fixed\"},"
                + "\"on_failure\":\"continue\",\"for_each\":{\"items\":\"$.input.orders\"}},"
                + "{\"id\":\"crm\",\"type\":\"mcp\",\"depends_on\":[],\"config\":{\"tool\":\"crm.upsert\"},"
                + "\"compensate\":{\"tool\":\"crm.upsert\",\"config\":{\"mode\":\"restore\"}},"
                + "\"schedule_to_close_s\":120}]}";

        FrozenDefinition frozen = freezer.freeze(DefinitionCodec.parse(DefinitionCodec.readTree(json)), "s", CEILINGS);

        assertThat(frozen.maxDurationS()).isEqualTo(60);
        assertThat(frozen.maxParallel()).isEqualTo(4);
        assertThat(frozen.limits()).isEqualTo(
                new FrozenDefinition.ExecutionLimits(new BigDecimal("1.5"), 1000, 500, 100));

        FrozenNode pay = frozen.node("pay").orElseThrow();
        assertThat(pay.retry()).isEqualTo(new FrozenDefinition.RetryPolicy(2, 500, Backoff.FIXED));
        assertThat(pay.scheduleToCloseS()).isEqualTo(10 * 2 + 1 + 5);
        assertThat(pay.onFailure()).isEqualTo(OnFailure.CONTINUE);
        assertThat(pay.forEach()).isEqualTo(new FrozenDefinition.ForEach("$.input.orders", 16));
        assertThat(pay.compensate().type()).isEqualTo("mcp");
        assertThat(pay.compensate().config().path("tool").asText()).isEqualTo("payments.refund");

        FrozenNode crm = frozen.node("crm").orElseThrow();
        assertThat(crm.dependsOn()).isEmpty();
        assertThat(crm.scheduleToCloseS()).isEqualTo(120);
        assertThat(crm.compensate().config().path("tool").asText()).isEqualTo("crm.upsert");
        assertThat(crm.compensate().config().path("mode").asText()).isEqualTo("restore");
    }

    @Test
    void fanoutCeilingIsCappedByPlatformWidth() {
        TenantLimits generous = new TenantLimits(new TenantId("t"), BigDecimal.ONE, 1, 1, BigDecimal.ONE, 1, 1,
                1000, 1, 10);

        FrozenDefinition frozen = freezer.freeze(Fixtures.pdfExampleDefinition(), "s", generous);

        assertThat(frozen.limits().maxFanout()).isEqualTo(100);
    }
}
