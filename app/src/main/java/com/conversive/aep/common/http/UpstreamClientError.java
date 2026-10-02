package com.conversive.aep.common.http;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A 4xx other than 429 ({@code UPSTREAM_CLIENT_ERROR}). Keeps the status and body so a caller can
 * reinterpret specific answers, e.g. the side-effect guard reads 409 "in progress" as {@code EFFECT_IN_PROGRESS}.
 */
public class UpstreamClientError extends NonRetryableError {

    private final int status;
    private final transient JsonNode body;

    public UpstreamClientError(int status, JsonNode body, String message) {
        super(ErrorCodes.UPSTREAM_CLIENT_ERROR, message);
        this.status = status;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public JsonNode body() {
        return body;
    }
}
