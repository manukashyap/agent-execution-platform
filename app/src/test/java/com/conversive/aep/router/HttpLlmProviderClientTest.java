package com.conversive.aep.router;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.http.OutboundClient;
import com.conversive.aep.common.http.OutboundProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpLlmProviderClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static WireMockServer wireMock;

    private HttpLlmProviderClient client;
    private ProviderConfig provider;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        OutboundProperties properties = new OutboundProperties(List.of("localhost:" + wireMock.port()), List.of(),
                Duration.ofSeconds(1));
        client = new HttpLlmProviderClient(new OutboundClient(properties, MAPPER, Clock.systemUTC()), MAPPER);
        provider = new ProviderConfig("llm-a", URI.create(wireMock.baseUrl() + "/llm/llm-a/"), "model-a",
                Duration.ofMillis(200), new BigDecimal("0.010"), 10, Set.of("chat", "tools"));
    }

    private static LlmProviderClient.Call call(List<LlmToolSpec> tools) {
        return new LlmProviderClient.Call("model-a", List.of(LlmMessage.of("system", "be brief"), LlmMessage.user("hi")),
                tools, Duration.ofSeconds(2), ExecutionMode.LIVE);
    }

    @Test
    void postsAnOpenAiShapedRequestAndParsesTheReply() {
        wireMock.stubFor(post(urlEqualTo("/llm/llm-a/v1/chat/completions")).willReturn(okJson("""
                {"choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}
                """)));
        LlmToolSpec tool = new LlmToolSpec("crm_lookup", "find a contact", MAPPER.createObjectNode().put("type", "object"));

        LlmProviderClient.Reply reply = client.complete(provider, call(List.of(tool)));

        assertThat(reply.content()).isEqualTo("hello");
        assertThat(reply.finishReason()).isEqualTo("stop");
        assertThat(reply.totalTokens()).isEqualTo(15);
        wireMock.verify(postRequestedFor(urlEqualTo("/llm/llm-a/v1/chat/completions")).withRequestBody(equalToJson("""
                {"model":"model-a",
                 "messages":[{"role":"system","content":"be brief"},{"role":"user","content":"hi"}],
                 "tools":[{"type":"function","function":{"name":"crm_lookup","description":"find a contact",
                           "parameters":{"type":"object"}}}]}
                """)));
    }

    @Test
    void parsesToolCalls() throws Exception {
        LlmProviderClient.Reply reply = HttpLlmProviderClient.parse("llm-a", MAPPER.readTree("""
                {"choices":[{"message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call_1","type":"function","function":{"name":"crm_lookup","arguments":"{\\"q\\":\\"x\\"}"}}]},
                  "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
                """));

        assertThat(reply.content()).isNull();
        assertThat(reply.toolCalls()).containsExactly(new LlmToolCall("call_1", "crm_lookup", "{\"q\":\"x\"}"));
    }

    @Test
    void malformedReplyIsRetryable() throws Exception {
        assertThatThrownBy(() -> HttpLlmProviderClient.parse("llm-a", MAPPER.readTree("{\"choices\":[]}")))
                .isInstanceOfSatisfying(RetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_UNAVAILABLE));
    }

    @Test
    void upstream5xxIsRetryable() {
        wireMock.stubFor(post(urlEqualTo("/llm/llm-a/v1/chat/completions")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.complete(provider, call(List.of())))
                .isInstanceOfSatisfying(RetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.UPSTREAM_UNAVAILABLE));
    }
}
