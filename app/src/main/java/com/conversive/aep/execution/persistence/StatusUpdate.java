package com.conversive.aep.execution.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

/**
 * Columns written together with a status transition. {@code at} becomes {@code updated_at}, and
 * also {@code started_at} (entering RUNNING, if unset) or {@code ended_at} (entering a terminal status).
 * Null error/output fields leave the stored values unchanged.
 */
public record StatusUpdate(Instant at, String errorCode, String errorMessage, JsonNode output) {

    public StatusUpdate {
        Objects.requireNonNull(at, "at");
    }

    public static StatusUpdate at(Instant at) {
        return new StatusUpdate(at, null, null, null);
    }

    public static StatusUpdate failure(Instant at, String errorCode, String errorMessage) {
        return new StatusUpdate(at, errorCode, errorMessage, null);
    }
}
