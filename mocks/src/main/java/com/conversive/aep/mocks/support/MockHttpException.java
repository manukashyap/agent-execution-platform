package com.conversive.aep.mocks.support;

import java.util.Map;

/** A deliberate HTTP-level failure (injected or semantic) raised by mock logic. */
public class MockHttpException extends RuntimeException {

    private final int status;
    private final transient Map<String, Object> body;
    private final transient Map<String, String> headers;

    public MockHttpException(int status, String error) {
        this(status, error, Map.of());
    }

    public MockHttpException(int status, String error, Map<String, String> headers) {
        super(error);
        this.status = status;
        this.body = Map.of("error", error);
        this.headers = headers;
    }

    public int status() {
        return status;
    }

    public Map<String, Object> body() {
        return body;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
