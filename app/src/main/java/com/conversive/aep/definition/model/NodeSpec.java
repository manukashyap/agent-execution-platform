package com.conversive.aep.definition.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One node as submitted. {@code dependsOn == null} means "implicit": the node depends on the
 * previous node in list order; an empty list marks an explicit root.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodeSpec(
        String id,
        String type,
        JsonNode config,
        @JsonProperty("depends_on") @JsonAlias("dependsOn") List<String> dependsOn,
        @JsonProperty("for_each") @JsonAlias("forEach") ForEachSpec forEach,
        RetrySpec retry,
        @JsonProperty("timeout_s") @JsonAlias("timeoutS") Integer timeoutS,
        @JsonProperty("schedule_to_close_s") @JsonAlias("scheduleToCloseS") Integer scheduleToCloseS,
        @JsonProperty("side_effecting") @JsonAlias("sideEffecting") Boolean sideEffecting,
        CompensateSpec compensate,
        Boolean pivot,
        @JsonProperty("on_failure") @JsonAlias("onFailure") OnFailure onFailure) {

    public NodeSpec {
        dependsOn = dependsOn == null ? null : Collections.unmodifiableList(new ArrayList<>(dependsOn));
    }

    /** Minimal node in the PDF shape ({@code id, type, config}); used by tests and fixtures. */
    public static NodeSpec of(String id, String type, JsonNode config) {
        return new NodeSpec(id, type, config, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Item source and bound for a fan-out node.
     *
     * @param items path into upstream output, e.g. {@code $.fetch_leads.leads}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForEachSpec(
            String items,
            @JsonProperty("max_concurrency") @JsonAlias("maxConcurrency") Integer maxConcurrency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetrySpec(
            @JsonProperty("max_attempts") @JsonAlias("maxAttempts") Integer maxAttempts,
            @JsonProperty("initial_interval_ms") @JsonAlias("initialIntervalMs") Long initialIntervalMs,
            Backoff backoff) {
    }

    /**
     * Compensation action. {@code tool} is shorthand for {@code type = "mcp"} with
     * {@code config.tool = tool}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompensateSpec(String type, String tool, JsonNode config) {
    }
}
