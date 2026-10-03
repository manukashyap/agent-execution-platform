package com.conversive.aep.dryrun;

import com.conversive.aep.common.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A deterministic JSON instance of a JSON Schema: every choice is drawn from {@code sha256(seed | path)}, so the
 * same seed always yields the same document. Covers {@code const}, {@code enum}, object, array, string (with the
 * common formats), integer, number, boolean and null; deeper than {@link #MAX_DEPTH} levels it emits null.
 */
public final class SchemaSampler {

    static final int MAX_DEPTH = 8;
    private static final int MAX_ITEMS = 3;
    private static final int DEFAULT_RANGE = 100;
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private SchemaSampler() {
    }

    public static JsonNode sample(JsonNode schema, String seed) {
        return sample(schema == null ? JSON.objectNode() : schema, seed, "$", 0);
    }

    private static JsonNode sample(JsonNode schema, String seed, String path, int depth) {
        if (depth > MAX_DEPTH) {
            return JSON.nullNode();
        }
        if (schema.has("const")) {
            return schema.get("const").deepCopy();
        }
        JsonNode choices = schema.path("enum");
        if (choices.isArray() && !choices.isEmpty()) {
            return choices.get((int) (draw(seed, path) % choices.size())).deepCopy();
        }
        return switch (type(schema)) {
            case "object" -> object(schema, seed, path, depth);
            case "array" -> array(schema, seed, path, depth);
            case "string" -> JSON.textNode(string(schema, seed, path));
            case "integer" -> JSON.numberNode(integer(schema, seed, path));
            case "number" -> JSON.numberNode(BigDecimal.valueOf(integer(schema, seed, path)));
            case "boolean" -> JSON.booleanNode(draw(seed, path) % 2 == 0);
            default -> JSON.nullNode();
        };
    }

    private static String type(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isTextual()) {
            return type.asText();
        }
        for (JsonNode t : type) {
            if (!"null".equals(t.asText())) {
                return t.asText();
            }
        }
        return schema.has("properties") ? "object" : schema.has("items") ? "array" : "null";
    }

    private static ObjectNode object(JsonNode schema, String seed, String path, int depth) {
        ObjectNode out = JSON.objectNode();
        schema.path("properties").properties().forEach(p ->
                out.set(p.getKey(), sample(p.getValue(), seed, path + "." + p.getKey(), depth + 1)));
        return out;
    }

    private static ArrayNode array(JsonNode schema, String seed, String path, int depth) {
        int min = schema.path("minItems").asInt(1);
        int max = schema.path("maxItems").asInt(MAX_ITEMS);
        int count = Math.max(0, Math.min(Math.max(min, 1), Math.min(max, MAX_ITEMS)));
        JsonNode items = schema.path("items").isObject() ? schema.get("items") : JSON.objectNode();
        ArrayNode out = JSON.arrayNode();
        for (int i = 0; i < count; i++) {
            out.add(sample(items, seed, path + "[" + i + "]", depth + 1));
        }
        return out;
    }

    private static String string(JsonNode schema, String seed, String path) {
        String hex = Hashing.sha256Hex(seed + "|" + path);
        String name = path.substring(path.lastIndexOf('.') + 1).replaceAll("[^A-Za-z0-9_]", "");
        return switch (schema.path("format").asText("")) {
            case "email" -> "dry-run-" + hex.substring(0, 8) + "@example.com";
            case "date-time" -> "2026-01-01T00:00:00Z";
            case "date" -> "2026-01-01";
            case "uri", "url" -> "https://example.com/dry-run/" + hex.substring(0, 8);
            case "uuid" -> UUID.nameUUIDFromBytes(hex.getBytes(StandardCharsets.UTF_8)).toString();
            default -> (name.isEmpty() ? "value" : name) + "-" + hex.substring(0, 8);
        };
    }

    private static long integer(JsonNode schema, String seed, String path) {
        long min = schema.path("minimum").asLong(0);
        long max = schema.has("maximum") ? schema.path("maximum").asLong() : min + DEFAULT_RANGE;
        long range = Math.max(1, max - min + 1);
        return min + draw(seed, path) % range;
    }

    /** A non-negative number derived from the seed and the JSON path. */
    static long draw(String seed, String path) {
        return Long.parseLong(Hashing.sha256Hex(seed + "|" + path).substring(0, 12), 16);
    }
}
