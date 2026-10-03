package com.conversive.aep.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** Every /v1 response body: {@code {data, error:{code,message}, meta}}. Exactly one of data/error is set. */
public record ApiEnvelope<T>(T data, ApiError error, Map<String, Object> meta) {

    public ApiEnvelope {
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public static <T> ApiEnvelope<T> ok(T data) {
        return new ApiEnvelope<>(data, null, Map.of());
    }

    public static <T> ApiEnvelope<T> ok(T data, Map<String, Object> meta) {
        return new ApiEnvelope<>(data, null, meta);
    }

    public static ApiEnvelope<Void> error(String code, String message) {
        return new ApiEnvelope<>(null, new ApiError(code, message, null), Map.of());
    }

    public static ApiEnvelope<Void> error(String code, String message, List<?> details) {
        return new ApiEnvelope<>(null, new ApiError(code, message, details), Map.of());
    }

    /** @param details per-finding list (validation errors); omitted when null */
    public record ApiError(String code, String message,
                           @JsonInclude(JsonInclude.Include.NON_NULL) List<?> details) {

        public ApiError {
            details = details == null ? null : List.copyOf(details);
        }
    }
}
