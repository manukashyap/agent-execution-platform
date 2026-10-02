package com.conversive.aep.nodes.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.nodes.NodeResult;
import com.conversive.aep.nodes.ExecutorRegistry;
import com.conversive.aep.support.WireMockToolsIntegrationTest;
import com.conversive.aep.tools.ToolCallAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@code mcp} node: templated args from the node input → ToolGateway → WireMock {@code /mcp}. */
class McpToolExecutorIT extends WireMockToolsIntegrationTest {

    private static final TenantId DEV = TenantId.of("t_dev");

    @Autowired
    private McpToolExecutor executor;

    @Autowired
    private ExecutorRegistry registry;

    @Autowired
    private ToolCallAudit audit;

    @Autowired
    private ObjectMapper mapper;

    private NodeContext context(ExecutionId exec, String config, String input) throws Exception {
        return new NodeContext(DEV, exec, "wf_1", 1, "fetch", McpToolExecutor.TYPE, 0, 1, Phase.FORWARD,
                ExecutionMode.LIVE, false, mapper.readTree(config), mapper.readTree(input), Duration.ofSeconds(10),
                Priority.NORMAL, null);
    }

    @Test
    void rendersArgsFromTheInputAndReturnsTheToolResultAsOutput() throws Exception {
        WIRE_MOCK.stubFor(post(urlEqualTo("/mcp"))
                .willReturn(okJson(toolResult("{\"contacts\":[{\"external_ref\":\"lead_7\"}]}", false))));
        ExecutionId exec = ExecutionId.random();

        NodeResult result = executor.execute(context(exec,
                "{\"tool\":\"crm.get\",\"args\":{\"external_ref\":\"{{lead.ref}}\"}}", "{\"lead\":{\"ref\":\"lead_7\"}}"));

        assertThat(result.output().path("contacts").get(0).path("external_ref").asText()).isEqualTo("lead_7");
        assertThat(result.costUsd()).isZero();
        assertThat(result.tokens()).isZero();
        assertThat(result.meta()).containsEntry("tool", "crm.get");
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/mcp"))
                .withRequestBody(matchingJsonPath("$.params.arguments.external_ref", equalTo("lead_7"))));
        assertThat(audit.findByExecution(DEV, exec)).hasSize(1);
        assertThat(result.output().toString()).doesNotContain(TEST_CREDENTIAL);
    }

    @Test
    void isRegisteredUnderTheMcpType() throws Exception {
        assertThat(registry.resolve(context(ExecutionId.random(), "{}", "{}"))).isSameAs(executor);
    }

    @Test
    void missingToolIsAValidationFailure() throws Exception {
        assertThatThrownBy(() -> executor.execute(context(ExecutionId.random(), "{\"args\":{}}", "{}")))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED));
    }
}
