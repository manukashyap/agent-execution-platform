package com.conversive.aep.common.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.NonRetryableError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DNS rebinding: the connection must use exactly the addresses the policy validated. */
class OutboundClientRebindingTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final String PUBLIC_IP = "93.184.216.34";

    private WireMockServer internal;

    @BeforeEach
    void startInternalServer() {
        internal = new WireMockServer(options().dynamicPort());
        internal.start();
        internal.stubFor(get(urlEqualTo("/secret")).willReturn(okJson("{\"leak\":true}")));
    }

    @AfterEach
    void stopInternalServer() {
        internal.stop();
    }

    @Test
    void aRebindingNameIsResolvedOnceSoTheInternalSecondAnswerIsNeverUsed() {
        List<String> answers = new ArrayList<>(List.of(PUBLIC_IP, "127.0.0.1", "169.254.169.254"));
        AtomicInteger lookups = new AtomicInteger();
        OutboundClient client = client(List.of(), host -> {
            lookups.incrementAndGet();
            return addresses(answers.size() > 1 ? answers.remove(0) : answers.get(0));
        });

        assertThatThrownBy(() -> client.send(live("http://rebind.test:" + internal.port() + "/secret")))
                .isNotInstanceOf(NonRetryableError.class);

        assertThat(lookups).hasValue(1);
        assertThat(internal.getAllServeEvents()).isEmpty();
    }

    @Test
    void aHostAnsweringInternalOnTheLookupThatConnectsIsDenied() {
        OutboundClient client = client(List.of(), host -> addresses("127.0.0.1"));

        assertDenied(client, live("http://rebind.test:" + internal.port() + "/secret"));
        assertThat(internal.getAllServeEvents()).isEmpty();
    }

    @Test
    void aHostResolvingToMixedPublicAndPrivateAddressesIsDenied() {
        OutboundClient client = client(List.of(), host -> addresses(PUBLIC_IP, "127.0.0.1"));

        assertDenied(client, live("http://mixed.test:" + internal.port() + "/secret"));
        assertThat(internal.getAllServeEvents()).isEmpty();
    }

    @Test
    void aRedirectToAnInternalAddressIsNotFollowed() {
        OutboundClient client = client(List.of("localhost:" + internal.port()), EgressPolicy.SYSTEM_RESOLVER);
        internal.stubFor(get("/hop").willReturn(aResponse().withStatus(302)
                .withHeader("Location", "http://169.254.169.254/latest/meta-data/")));

        OutboundResponse response = client.send(live("http://localhost:" + internal.port() + "/hop"));

        assertThat(response.status()).isEqualTo(302);
        assertThat(internal.getAllServeEvents()).hasSize(1);
    }

    @Test
    void anAllowListedHostResolvingToLoopbackStillWorksWithItsOriginalHostHeader() {
        OutboundClient client = client(List.of("mocks:" + internal.port()), host -> addresses("127.0.0.1"));

        OutboundResponse response = client.send(live("http://mocks:" + internal.port() + "/secret"));

        assertThat(response.status()).isEqualTo(200);
        internal.verify(getRequestedFor(urlEqualTo("/secret")).withHeader("Host", equalTo("mocks:" + internal.port())));
    }

    @Test
    void anAllowListEntryWithAnotherPortDoesNotExemptTheHost() {
        OutboundClient client = client(List.of("mocks:9999"), host -> addresses("127.0.0.1"));

        assertDenied(client, live("http://mocks:" + internal.port() + "/secret"));
        assertThat(internal.getAllServeEvents()).isEmpty();
    }

    private static OutboundClient client(List<String> allow, EgressPolicy.Resolver resolver) {
        OutboundProperties props = new OutboundProperties(allow, List.of(), Duration.ofMillis(500));
        return new OutboundClient(props, new ObjectMapper(), Clock.systemUTC(), resolver);
    }

    private static OutboundRequest live(String url) {
        return OutboundRequest.get(URI.create(url), TIMEOUT, ExecutionMode.LIVE);
    }

    private static InetAddress[] addresses(String... literals) {
        try {
            InetAddress[] result = new InetAddress[literals.length];
            for (int i = 0; i < literals.length; i++) {
                result[i] = InetAddress.getByName(literals[i]);
            }
            return result;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static void assertDenied(OutboundClient client, OutboundRequest request) {
        assertThatThrownBy(() -> client.send(request))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.EGRESS_DENIED))
                .hasMessageContaining("non-public");
    }
}
