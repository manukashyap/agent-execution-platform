package com.conversive.aep.api.dto;

import com.conversive.aep.definition.StoredDefinition;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** A published definition; {@code spec} is included on GET only. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DefinitionView(String workflowId, int version, String sha256, Instant createdAt, JsonNode spec) {

    public static DefinitionView summary(StoredDefinition d) {
        return new DefinitionView(d.workflowId(), d.version(), d.sha256(), d.createdAt(), null);
    }

    public static DefinitionView full(StoredDefinition d) {
        return new DefinitionView(d.workflowId(), d.version(), d.sha256(), d.createdAt(), d.spec());
    }
}
