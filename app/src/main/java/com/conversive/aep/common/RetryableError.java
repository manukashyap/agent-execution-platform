package com.conversive.aep.common;

import java.time.Duration;
import java.util.Objects;

/** A failure that may succeed on a later attempt; {@code nextRetryDelay} overrides the retry policy backoff. */
public class RetryableError extends RuntimeException {

    private final String code;
    private final Duration nextRetryDelay;

    public RetryableError(String code, String message) {
        this(code, message, null, null);
    }

    public RetryableError(String code, String message, Duration nextRetryDelay) {
        this(code, message, nextRetryDelay, null);
    }

    public RetryableError(String code, String message, Duration nextRetryDelay, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.nextRetryDelay = nextRetryDelay;
    }

    public String code() {
        return code;
    }

    public Duration nextRetryDelay() {
        return nextRetryDelay;
    }
}
