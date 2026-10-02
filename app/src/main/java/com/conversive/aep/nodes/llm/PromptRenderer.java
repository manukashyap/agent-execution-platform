package com.conversive.aep.nodes.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code {{a.b.0.c}}} with the value at that dotted path in the node input. Text values are
 * inserted as is, other JSON values as compact JSON, missing paths as the empty string.
 */
public final class PromptRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private PromptRenderer() {
    }

    public static String render(String template, JsonNode input) {
        if (template == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(valueAt(input, m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String valueAt(JsonNode input, String path) {
        JsonNode node = input;
        for (String segment : path.split("\\.")) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return "";
            }
            node = node.isArray() && !segment.isEmpty() && segment.length() <= 9 && segment.chars().allMatch(Character::isDigit)
                    ? node.path(Integer.parseInt(segment))
                    : node.path(segment);
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.isTextual() ? node.asText() : node.toString();
    }
}
