package com.conversive.aep.router;

import com.fasterxml.jackson.databind.JsonNode;

/** A function the model may call; {@code parameters} is a JSON schema. */
public record LlmToolSpec(String name, String description, JsonNode parameters) {
}
