package com.conversive.aep.definition.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/** {@code FAIL_FAST} fails the execution (and compensates); {@code CONTINUE} skips the node's dependants. */
public enum OnFailure {
    FAIL_FAST, CONTINUE;

    /** Accepts {@code FAIL_WORKFLOW} (the name used in 01/design.md) as an alias of {@code FAIL_FAST}. */
    @JsonCreator
    public static OnFailure fromJson(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "FAIL_WORKFLOW".equals(normalized) ? FAIL_FAST : valueOf(normalized);
    }
}
