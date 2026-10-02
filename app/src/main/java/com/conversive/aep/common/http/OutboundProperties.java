package com.conversive.aep.common.http;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * @param allowHosts            hosts exempt from the SSRF deny-list and allowed in non-LIVE modes ({@code host} or {@code host:port})
 * @param selfHosts             the platform's own API host(s); always refused to block self-triggering loops
 * @param maxConnTotal          pooled connections across all routes; sized for activity slots x fan-out
 * @param maxConnPerRoute       pooled connections to one host:port; compose and bootRun send everything to one route
 * @param connectionRequestTimeout how long a call waits for a free pooled connection before it fails as never sent
 * @param maxResponseBytes      largest response body read; a bigger one fails as non-retryable RESPONSE_TOO_LARGE
 */
@ConfigurationProperties("aep.outbound")
public record OutboundProperties(List<String> allowHosts, List<String> selfHosts, Duration connectTimeout,
                                 Integer maxConnTotal, Integer maxConnPerRoute, Duration connectionRequestTimeout,
                                 Integer maxResponseBytes) {

    static final int DEFAULT_MAX_CONN_TOTAL = 400;
    static final int DEFAULT_MAX_CONN_PER_ROUTE = 200;
    static final int DEFAULT_MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    @ConstructorBinding
    public OutboundProperties {
        allowHosts = allowHosts == null ? List.of() : List.copyOf(allowHosts);
        selfHosts = selfHosts == null ? List.of() : List.copyOf(selfHosts);
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        maxConnTotal = positiveOr(maxConnTotal, DEFAULT_MAX_CONN_TOTAL);
        maxConnPerRoute = positiveOr(maxConnPerRoute, DEFAULT_MAX_CONN_PER_ROUTE);
        connectionRequestTimeout = connectionRequestTimeout == null ? Duration.ofSeconds(1) : connectionRequestTimeout;
        maxResponseBytes = positiveOr(maxResponseBytes, DEFAULT_MAX_RESPONSE_BYTES);
    }

    /** Pool limits, pool-wait timeout and body cap at their defaults. */
    public OutboundProperties(List<String> allowHosts, List<String> selfHosts, Duration connectTimeout) {
        this(allowHosts, selfHosts, connectTimeout, null, null, null, null);
    }

    public OutboundProperties withMaxResponseBytes(int bytes) {
        return new OutboundProperties(allowHosts, selfHosts, connectTimeout, maxConnTotal, maxConnPerRoute,
                connectionRequestTimeout, bytes);
    }

    private static int positiveOr(Integer value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("pool and body limits must be positive");
        }
        return value;
    }
}
