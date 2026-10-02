package com.conversive.aep.common;

import java.util.Objects;

/** A failure that no retry can fix (validation, authorization, limit breach, 4xx). */
public class NonRetryableError extends RuntimeException {

    private final String code;

    public NonRetryableError(String code, String message) {
        this(code, message, null);
    }

    public NonRetryableError(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
