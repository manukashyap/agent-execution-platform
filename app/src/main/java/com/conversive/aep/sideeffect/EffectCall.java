package com.conversive.aep.sideeffect;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

public interface EffectCall {

    JsonNode invoke(String idempotencyKey);

    /** For {@code LOOKUP} tools: find an effect that may already have happened under this key. */
    default Optional<JsonNode> lookup() {
        return Optional.empty();
    }
}
