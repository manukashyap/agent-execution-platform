package com.conversive.aep.common.http;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param allowHosts hosts exempt from the SSRF deny-list and allowed in non-LIVE modes ({@code host} or {@code host:port})
 * @param selfHosts  the platform's own API host(s); always refused to block self-triggering loops
 */
@ConfigurationProperties("aep.outbound")
public record OutboundProperties(List<String> allowHosts, List<String> selfHosts, Duration connectTimeout) {

    public OutboundProperties {
        allowHosts = allowHosts == null ? List.of() : List.copyOf(allowHosts);
        selfHosts = selfHosts == null ? List.of() : List.copyOf(selfHosts);
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
    }
}
