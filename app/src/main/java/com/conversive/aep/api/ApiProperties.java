package com.conversive.aep.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param startFailedRetryAfterS {@code Retry-After} when an error carries no delay of its own */
@ConfigurationProperties("aep.api")
public record ApiProperties(int startFailedRetryAfterS) {

    public ApiProperties {
        startFailedRetryAfterS = startFailedRetryAfterS <= 0 ? 5 : startFailedRetryAfterS;
    }
}
