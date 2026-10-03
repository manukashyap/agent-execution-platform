package com.conversive.aep.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Postgres plus one WireMock standing in for the mocks service: JSON-RPC {@code /mcp} and the three LLM providers at
 * {@code /llm/<provider>}. Shared by the P4 tool tests so they reuse a single Spring context.
 */
@ActiveProfiles("dev")
public abstract class WireMockToolsIntegrationTest extends PostgresIntegrationTest {

    /** Obviously fake; tests grep for it to prove the credential never leaks past the Authorization header. */
    public static final String TEST_CREDENTIAL = "test-only-tool-credential-7f3a";
    protected static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    static {
        WIRE_MOCK.start();
        Runtime.getRuntime().addShutdownHook(new Thread(WIRE_MOCK::stop));
    }

    @DynamicPropertySource
    static void wireMockServices(DynamicPropertyRegistry registry) {
        registry.add("aep.tools.mcp-url", () -> WIRE_MOCK.baseUrl() + "/mcp");
        registry.add("aep.tools.dev-credential", () -> TEST_CREDENTIAL);
        registry.add("aep.outbound.allow-hosts", () -> "localhost:" + WIRE_MOCK.port());
        for (String p : List.of("llm-a", "llm-b", "vllm")) {
            registry.add("aep.router.providers." + p + ".base-url", () -> WIRE_MOCK.baseUrl() + "/llm/" + p);
        }
    }

    @BeforeEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
    }

    /** A successful MCP {@code tools/call} answer carrying {@code json}. */
    public static String toolResult(String json, boolean isError) {
        return """
                {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"json","json":%s}],"isError":%s}}
                """.formatted(json, isError);
    }

    public static String rpcError(int code) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":" + code + ",\"message\":\"x\"}}";
    }
}
