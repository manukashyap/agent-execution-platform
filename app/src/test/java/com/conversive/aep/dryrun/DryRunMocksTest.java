package com.conversive.aep.dryrun;

import static com.conversive.aep.dryrun.DryRunFixtures.dryRun;
import static com.conversive.aep.dryrun.DryRunFixtures.json;
import static com.conversive.aep.dryrun.DryRunFixtures.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.conversive.aep.common.Reversibility;
import com.conversive.aep.nodes.DryRunOptions;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.tools.ToolCallAudit;
import com.conversive.aep.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T6.1: mock outputs come from the tool's example or schema and are deterministic from the seed. */
class DryRunMocksTest {

    private static final DryRunOptions MOCK_LLM = new DryRunOptions(true, true);
    private static final String SCHEMA = """
            {"type":"object","properties":{"id":{"type":"string"},"email":{"type":"string","format":"email"},
             "score":{"type":"integer","minimum":1,"maximum":5},"tier":{"enum":["hot","warm","cold"]},
             "tags":{"type":"array","items":{"type":"string"},"minItems":2}}}
            """;

    private final ToolRegistry tools = mock(ToolRegistry.class);
    private final DryRunMocks mocks = new DryRunMocks(tools, mock(ToolCallAudit.class));

    @Test
    void toolOutputPrefersTheDryRunExample() {
        when(tools.find("crm.upsert")).thenReturn(Optional.of(
                tool("crm.upsert", Reversibility.COMPENSATABLE, SCHEMA, "{\"id\":\"c_1\"}")));

        JsonNode out = mocks.execute(dryRun("mcp", "{\"tool\":\"crm.upsert\"}", true, MOCK_LLM)).output();

        assertThat(out).isEqualTo(json("{\"id\":\"c_1\"}"));
    }

    @Test
    void toolOutputFallsBackToASeededSchemaInstance() {
        when(tools.find("crm.upsert")).thenReturn(Optional.of(
                tool("crm.upsert", Reversibility.COMPENSATABLE, SCHEMA, null)));
        NodeContext ctx = dryRun("mcp", "{\"tool\":\"crm.upsert\"}", true, MOCK_LLM);

        JsonNode first = mocks.execute(ctx).output();
        JsonNode second = mocks.execute(ctx).output();

        assertThat(first).isEqualTo(second);
        assertThat(first.path("email").asText()).endsWith("@example.com");
        assertThat(first.path("score").asInt()).isBetween(1, 5);
        assertThat(first.path("tier").asText()).isIn("hot", "warm", "cold");
        assertThat(first.path("tags")).hasSize(2);
    }

    @Test
    void mockedLlmIsDeterministicAndFree() {
        NodeContext ctx = dryRun("llm", "{\"prompt\":\"classify\"}", false, MOCK_LLM);

        var first = mocks.execute(ctx);
        var second = mocks.execute(ctx);

        assertThat(first.output()).isEqualTo(second.output());
        assertThat(first.output().path("provider").asText()).isEqualTo("mock");
        assertThat(first.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(first.tokens()).isZero();
    }

    @Test
    void seedDependsOnTheInput() {
        NodeContext a = dryRun("llm", "{}", false, MOCK_LLM);
        NodeContext b = new NodeContext(a.tenantId(), a.executionId(), a.workflowId(), a.defVersion(), a.nodeId(),
                a.nodeType(), a.callIndex(), a.attempt(), a.phase(), a.mode(), a.sideEffecting(), a.config(),
                json("{\"input\":{\"x\":2}}"), a.startToClose(), a.priority(), a.dryRun());

        assertThat(DryRunMocks.seed(a)).isNotEqualTo(DryRunMocks.seed(b)).hasSize(64);
    }

    @Test
    void mockedHttpAnswers200WithoutCallingOut() {
        JsonNode out = mocks.execute(dryRun("http", "{\"url\":\"http://x/y\",\"method\":\"POST\"}", false,
                MOCK_LLM)).output();

        assertThat(out.path("status").asInt()).isEqualTo(200);
        assertThat(out.path("body").path("dry_run").asBoolean()).isTrue();
    }

    @Test
    void mockedCallTargetsNameWhatWouldHaveBeenCalled() {
        MockedCall http = MockedCall.of(dryRun("http", "{\"url\":\"http://x/{{input.x}}\",\"method\":\"post\"}",
                false, MOCK_LLM));
        MockedCall mcp = MockedCall.of(dryRun("mcp", "{\"tool\":\"crm.upsert\",\"args\":{\"x\":\"{{input.x}}\"}}",
                true, MOCK_LLM));

        assertThat(http.target()).isEqualTo("POST http://x/1");
        assertThat(mcp.target()).isEqualTo("crm.upsert");
        assertThat(mcp.args()).isEqualTo(json("{\"x\":1}"));
        assertThat(MockedCall.fromAuditName(http.auditName())).contains(new MockedCall("http", "POST http://x/1", null));
        assertThat(MockedCall.fromAuditName("crm.upsert")).isEmpty();
    }
}
