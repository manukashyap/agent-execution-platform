package com.conversive.aep.definition.model;

import com.conversive.aep.common.Hashing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Parsing, canonical hashing and implicit-dependency resolution for submitted definitions. */
public final class DefinitionCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private DefinitionCodec() {
    }

    /** @throws IllegalArgumentException with a client-safe message when the JSON does not fit the model */
    public static WorkflowDefinition parse(JsonNode spec) {
        if (spec == null || !spec.isObject()) {
            throw new IllegalArgumentException("definition must be a JSON object");
        }
        try {
            return MAPPER.treeToValue(spec, WorkflowDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("definition does not match the schema: " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** SHA-256 of the spec with object keys sorted recursively, so key order and whitespace do not matter. */
    public static String sha256(JsonNode spec) {
        try {
            return Hashing.sha256Hex(MAPPER.writeValueAsString(canonical(spec)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise spec", e);
        }
    }

    /**
     * Dependencies per node, index-aligned with {@code nodes}: an absent {@code depends_on} means the
     * previous node in list order (none for the first node); {@code []} is an explicit root.
     */
    public static List<List<String>> resolveDependencies(List<NodeSpec> nodes) {
        List<List<String>> resolved = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            NodeSpec node = nodes.get(i);
            if (node != null && node.dependsOn() != null) {
                resolved.add(List.copyOf(node.dependsOn()));
            } else if (i == 0 || nodes.get(i - 1) == null || nodes.get(i - 1).id() == null) {
                resolved.add(List.of());
            } else {
                resolved.add(List.of(nodes.get(i - 1).id()));
            }
        }
        return List.copyOf(resolved);
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(e -> sorted.put(e.getKey(), canonical(e.getValue())));
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> out.add(canonical(child)));
            return out;
        }
        return node;
    }
}
