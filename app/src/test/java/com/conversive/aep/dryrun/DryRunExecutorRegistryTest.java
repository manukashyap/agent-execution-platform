package com.conversive.aep.dryrun;

import static com.conversive.aep.dryrun.DryRunFixtures.TENANT;
import static com.conversive.aep.dryrun.DryRunFixtures.ctx;
import static com.conversive.aep.dryrun.DryRunFixtures.dryRun;
import static com.conversive.aep.dryrun.DryRunFixtures.json;
import static com.conversive.aep.dryrun.DryRunFixtures.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.Reversibility;
import com.conversive.aep.common.http.NonLiveEgress;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.definition.StoredDefinition;
import com.conversive.aep.nodes.DryRunOptions;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.nodes.NodeExecutor;
import com.conversive.aep.nodes.NodeResult;
import com.conversive.aep.tools.ToolCallAudit;
import com.conversive.aep.tools.ToolRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T6.1: the 06 §4.10 policy table as resolved by the registry. */
class DryRunExecutorRegistryTest {

    private static final DryRunOptions DEFAULTS = DryRunOptions.defaults();
    private static final String READ_TOOL = "{\"tool\":\"leads.fetch\"}";
    private static final String WRITE_TOOL = "{\"tool\":\"crm.upsert\"}";
    private static final String GET = "{\"url\":\"http://mocks/leads\",\"method\":\"GET\"}";

    private final ToolRegistry tools = mock(ToolRegistry.class);
    private final DefinitionService definitions = mock(DefinitionService.class);
    private final ToolCallAudit audit = mock(ToolCallAudit.class);
    private final Recording http = new Recording("http");
    private final Recording llm = new Recording("llm");
    private final Recording mcp = new Recording("mcp");
    private DryRunExecutorRegistry registry;

    @BeforeEach
    void setUp() {
        when(tools.find("leads.fetch")).thenReturn(Optional.of(tool("leads.fetch", Reversibility.READ_ONLY,
                null, "{\"leads\":[]}")));
        when(tools.find("crm.upsert")).thenReturn(Optional.of(tool("crm.upsert", Reversibility.COMPENSATABLE,
                null, "{\"id\":\"c_1\"}")));
        registry = new DryRunExecutorRegistry(List.of(http, llm, mcp), new DryRunPolicy(tools, definitions),
                new DryRunMocks(tools, audit));
    }

    @Test
    void liveModeResolvesTheRealExecutorUnwrapped() {
        NodeContext live = ctx("mcp", WRITE_TOOL, ExecutionMode.LIVE, Phase.FORWARD, true, DEFAULTS);

        assertThat(registry.resolve(live)).isSameAs(mcp);
    }

    @Test
    void llmRunsLiveWithThePermitUnlessMockLlm() {
        assertRunsLive(dryRun("llm", "{}", false, DEFAULTS), llm);
        assertMocked(dryRun("llm", "{}", false, new DryRunOptions(true, true)), llm);
    }

    @Test
    void readOnlyToolRunsLiveUnlessAllowReadOnlyIsFalse() {
        assertRunsLive(dryRun("mcp", READ_TOOL, false, DEFAULTS), mcp);
        assertMocked(dryRun("mcp", READ_TOOL, false, new DryRunOptions(false, false)), mcp);
    }

    @Test
    void sideEffectingAndUnknownToolsAreMocked() {
        assertMocked(dryRun("mcp", WRITE_TOOL, true, DEFAULTS), mcp);
        assertMocked(dryRun("mcp", "{\"tool\":\"nope.unknown\"}", false, DEFAULTS), mcp);
    }

    @Test
    void httpDeclaredSideEffectingFalseRunsLiveUnlessAllowReadOnlyIsFalse() {
        spec("{\"nodes\":[{\"id\":\"n1\",\"type\":\"http\",\"side_effecting\":false}]}");

        assertRunsLive(dryRun("http", GET, false, DEFAULTS), http);
        assertMocked(dryRun("http", GET, false, new DryRunOptions(false, false)), http);
    }

    @Test
    void unclassifiedHttpIsMockedEvenForAGet() {
        spec("{\"nodes\":[{\"id\":\"n1\",\"type\":\"http\"}]}");

        assertMocked(dryRun("http", GET, false, DEFAULTS), http);
        assertMocked(dryRun("http", "{\"url\":\"http://mocks/crm\",\"method\":\"POST\"}", false, DEFAULTS), http);
    }

    @Test
    void declaredSideEffectingHttpIsMocked() {
        spec("{\"nodes\":[{\"id\":\"n1\",\"type\":\"http\",\"side_effecting\":true}]}");

        assertMocked(dryRun("http", GET, true, DEFAULTS), http);
    }

    @Test
    void compensationsAreAlwaysMocked() {
        NodeContext inverse = ctx("mcp", READ_TOOL, ExecutionMode.DRY_RUN, Phase.COMPENSATE, false, DEFAULTS);

        assertMocked(inverse, mcp);
    }

    @Test
    void replayModeFollowsTheSamePolicy() {
        assertMocked(ctx("mcp", WRITE_TOOL, ExecutionMode.REPLAY, Phase.FORWARD, true, DEFAULTS), mcp);
    }

    @Test
    void unknownNodeTypesAreRejectedInEveryMode() {
        assertThatThrownBy(() -> registry.resolve(dryRun("nope", "{}", false, DEFAULTS)))
                .isInstanceOf(NonRetryableError.class).hasMessageContaining("unknown node type");
    }

    @Test
    void mockedCallsAreAuditedAndNeverTouchTheRealExecutor() {
        NodeResult result = registry.resolve(dryRun("mcp", WRITE_TOOL, true, DEFAULTS))
                .execute(dryRun("mcp", WRITE_TOOL, true, DEFAULTS));

        assertThat(result.output()).isEqualTo(json("{\"id\":\"c_1\"}"));
        assertThat(result.meta()).containsEntry("mocked", "true");
        verify(audit).record(org.mockito.ArgumentMatchers.argThat(e ->
                e.toolName().equals("mock:mcp:crm.upsert") && e.tenantId().equals(TENANT)
                        && e.effectKey() == null));
        assertThat(mcp.calls).isZero();
    }

    private void spec(String spec) {
        when(definitions.get(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new StoredDefinition(TENANT, "wf", 1, json(spec), "sha", Instant.EPOCH));
    }

    private void assertRunsLive(NodeContext ctx, Recording real) {
        NodeExecutor resolved = registry.resolve(ctx);
        resolved.execute(ctx);
        assertThat(real.calls).isEqualTo(1);
        assertThat(real.permitted.get()).isTrue();
        assertThat(NonLiveEgress.permitted()).isFalse();
        real.calls = 0;
    }

    private void assertMocked(NodeContext ctx, Recording real) {
        NodeResult result = registry.resolve(ctx).execute(ctx);
        assertThat(real.calls).isZero();
        assertThat(result.meta()).containsEntry("mocked", "true");
        verify(audit, org.mockito.Mockito.atLeastOnce()).record(any());
    }

    /** Real executor stand-in that records whether it ran inside the egress permit. */
    private static final class Recording implements NodeExecutor {

        private final String type;
        private final AtomicReference<Boolean> permitted = new AtomicReference<>(false);
        private int calls;

        Recording(String type) {
            this.type = type;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public NodeResult execute(NodeContext ctx) {
            calls++;
            permitted.set(NonLiveEgress.permitted());
            return NodeResult.of(json("{}"));
        }
    }
}
