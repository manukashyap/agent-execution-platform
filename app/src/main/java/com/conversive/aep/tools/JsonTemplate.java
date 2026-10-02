package com.conversive.aep.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Non-executable JSON templating for tool args: a string that is exactly {@code {{a.b.0}}} becomes the JSON value at
 * that dotted path (keeping its type; an object field whose path is missing is dropped); placeholders inside longer
 * strings are interpolated as text. Nothing is evaluated.
 */
public final class JsonTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
    private static final int MAX_INDEX_DIGITS = 9;

    private JsonTemplate() {
    }

    public static JsonNode render(JsonNode template, JsonNode scope) {
        if (template == null || template.isNull() || template.isMissingNode()) {
            return JsonNodeFactory.instance.objectNode();
        }
        return renderNode(template, scope);
    }

    /** The value at a dotted path ({@code a.b.0.c}); {@link MissingNode} when absent or null. */
    public static JsonNode resolve(JsonNode root, String path) {
        JsonNode node = root == null ? MissingNode.getInstance() : root;
        for (String segment : path.split("\\.")) {
            if (node.isMissingNode() || node.isNull()) {
                return MissingNode.getInstance();
            }
            node = node.isArray() && isIndex(segment) ? node.path(Integer.parseInt(segment)) : node.path(segment);
        }
        return node.isNull() ? MissingNode.getInstance() : node;
    }

    /** Text form for interpolation: strings as is, other values as compact JSON, missing as empty. */
    public static String text(JsonNode root, String path) {
        JsonNode value = resolve(root, path);
        if (value.isMissingNode()) {
            return "";
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    public static String interpolate(String template, JsonNode scope) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(text(scope, m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static JsonNode renderNode(JsonNode node, JsonNode scope) {
        if (node.isTextual()) {
            return renderText(node.asText(), scope);
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                JsonNode value = renderNode(field.getValue(), scope);
                if (!value.isMissingNode()) {
                    out.set(field.getKey(), value);
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> {
                JsonNode value = renderNode(item, scope);
                out.add(value.isMissingNode() ? JsonNodeFactory.instance.nullNode() : value);
            });
            return out;
        }
        return node.deepCopy();
    }

    private static JsonNode renderText(String text, JsonNode scope) {
        Matcher whole = PLACEHOLDER.matcher(text);
        if (whole.matches()) {
            JsonNode value = resolve(scope, whole.group(1));
            return value.isMissingNode() ? value : value.deepCopy();
        }
        return JsonNodeFactory.instance.textNode(interpolate(text, scope));
    }

    private static boolean isIndex(String segment) {
        return !segment.isEmpty() && segment.length() <= MAX_INDEX_DIGITS && segment.chars().allMatch(Character::isDigit);
    }
}
