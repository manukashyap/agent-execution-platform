package com.conversive.aep.common.http;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.RetryableError;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The only HTTP egress of the platform (06 §2 rule 3). Names are resolved exactly once per connect, inside
 * {@link EgressPolicy#resolve}, and the connection goes to those validated addresses (Host header and TLS SNI keep
 * the original name), so DNS rebinding cannot swap in an internal address. Redirects are never followed.
 */
@Component
public class OutboundClient {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final ContentType JSON = ContentType.create("application/json");

    private final CloseableHttpClient http;
    private final EgressPolicy policy;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration connectionRequestTimeout;
    /** Whether the request sent on this thread targets an allow-listed host; the client connects on the caller's thread. */
    private final ThreadLocal<Boolean> allowListed = new ThreadLocal<>();

    @Autowired
    public OutboundClient(OutboundProperties properties, ObjectMapper mapper, Clock clock) {
        this(properties, mapper, clock, EgressPolicy.SYSTEM_RESOLVER);
    }

    OutboundClient(OutboundProperties properties, ObjectMapper mapper, Clock clock, EgressPolicy.Resolver resolver) {
        this.policy = new EgressPolicy(properties, resolver);
        this.http = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new ValidatingDnsResolver())
                        .setMaxConnTotal(properties.maxConnTotal())
                        .setMaxConnPerRoute(properties.maxConnPerRoute())
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.of(properties.connectTimeout())).build())
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .build();
        this.mapper = mapper;
        this.clock = clock;
        this.connectionRequestTimeout = properties.connectionRequestTimeout();
    }

    /** The client's only DNS lookup: resolves once and rejects non-public answers unless the target is allow-listed. */
    private final class ValidatingDnsResolver implements DnsResolver {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return policy.resolve(host, Boolean.TRUE.equals(allowListed.get()));
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }

    public OutboundResponse send(OutboundRequest request) {
        boolean exempt = policy.check(request.uri(), request.mode(), request.allowInNonLive() || NonLiveEgress.permitted());
        RawResponse response = exchange(request, exempt);
        OutboundResponse result = new OutboundResponse(response.status(), response.headers(), parse(response.body()));
        return classify(request, result);
    }

    private ClassicHttpRequest toHttpRequest(OutboundRequest request) {
        ClassicRequestBuilder builder = ClassicRequestBuilder.create(request.method())
                .setUri(request.uri())
                .addHeader("Accept", "application/json");
        if (request.body() != null) {
            builder.setEntity(write(request.body()).getBytes(StandardCharsets.UTF_8), JSON);
        }
        request.headers().forEach(builder::setHeader);
        if (request.idempotencyKey() != null) {
            builder.setHeader(IDEMPOTENCY_KEY, request.idempotencyKey());
        }
        return builder.build();
    }

    private RawResponse exchange(OutboundRequest request, boolean exempt) {
        String host = request.uri().getHost();
        HttpClientContext context = HttpClientContext.create();
        context.setRequestConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.of(request.timeout()))
                .setConnectionRequestTimeout(Timeout.of(connectionRequestTimeout))
                .build());
        allowListed.set(exempt);
        try {
            return http.execute(toHttpRequest(request), context, OutboundClient::readResponse);
        } catch (ConnectionRequestTimeoutException e) {
            throw new RetryableError(ErrorCodes.UPSTREAM_NOT_SENT, "no free connection to " + host, null, e);
        } catch (InterruptedIOException e) {
            throw new RetryableError(ErrorCodes.UPSTREAM_TIMEOUT, "timeout calling " + host, null, e);
        } catch (IOException e) {
            throw new RetryableError(ErrorCodes.UPSTREAM_IO, "I/O error calling " + host, null, e);
        } finally {
            allowListed.remove();
        }
    }

    private static RawResponse readResponse(ClassicHttpResponse response) throws IOException {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header header : response.getHeaders()) {
            headers.computeIfAbsent(header.getName(), k -> new ArrayList<>()).add(header.getValue());
        }
        try {
            String body = response.getEntity() == null ? null : EntityUtils.toString(response.getEntity());
            return new RawResponse(response.getCode(), headers, body);
        } catch (ParseException e) {
            throw new IOException("unreadable response body", e);
        }
    }

    private record RawResponse(int status, Map<String, List<String>> headers, String body) {
    }

    private OutboundResponse classify(OutboundRequest request, OutboundResponse response) {
        int status = response.status();
        String target = request.method() + " " + request.uri().getHost() + " -> " + status;
        if (status == 429) {
            throw new RetryableError(ErrorCodes.UPSTREAM_RATE_LIMITED, target, retryAfter(response).orElse(null));
        }
        if (status >= 500) {
            throw new RetryableError(ErrorCodes.UPSTREAM_UNAVAILABLE, target, retryAfter(response).orElse(null));
        }
        if (status >= 400) {
            throw new UpstreamClientError(status, response.body(), target);
        }
        return response;
    }

    Optional<Duration> retryAfter(OutboundResponse response) {
        return response.header("Retry-After").flatMap(this::parseRetryAfter);
    }

    private Optional<Duration> parseRetryAfter(String value) {
        String trimmed = value.trim();
        try {
            return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(trimmed))));
        } catch (NumberFormatException ignored) {
            // not delta-seconds; try the HTTP-date form below
        }
        try {
            Instant at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(clock.instant(), at);
            return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.nullNode();
        }
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            return mapper.getNodeFactory().textNode(body);
        }
    }

    private String write(JsonNode body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED, "request body is not serialisable", e);
        }
    }
}
