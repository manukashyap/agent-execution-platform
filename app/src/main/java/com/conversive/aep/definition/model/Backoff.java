package com.conversive.aep.definition.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

public enum Backoff {
    FIXED, EXPONENTIAL;

    /** Coefficient for Temporal's {@code RetryOptions.backoffCoefficient}. */
    public double coefficient() {
        return this == EXPONENTIAL ? 2.0 : 1.0;
    }

    @JsonCreator
    public static Backoff fromJson(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
