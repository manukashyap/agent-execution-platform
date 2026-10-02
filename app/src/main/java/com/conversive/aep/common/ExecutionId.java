package com.conversive.aep.common;

import java.util.Objects;
import java.util.UUID;

public record ExecutionId(UUID value) {

    public ExecutionId {
        Objects.requireNonNull(value, "executionId");
    }

    public static ExecutionId of(String value) {
        return new ExecutionId(UUID.fromString(value));
    }

    public static ExecutionId random() {
        return new ExecutionId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
