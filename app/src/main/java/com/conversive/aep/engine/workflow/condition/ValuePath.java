package com.conversive.aep.engine.workflow.condition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A reference into a node's output (or the execution input): {@code $.<root>(.<field> | [<index>])*},
 * e.g. {@code $.fetch_leads.leads[0].email}. {@code root} is a node id or {@link #INPUT}.
 * Segments are either {@code String} (object field) or {@code Integer} (array index).
 */
public record ValuePath(String root, List<Object> segments) {

    public static final String INPUT = "input";

    public ValuePath {
        Objects.requireNonNull(root, "root");
        segments = List.copyOf(segments);
    }

    /** @throws ConditionSyntaxException when {@code text} is not a valid path */
    public static ValuePath parse(String text) {
        if (text == null || !text.startsWith("$.")) {
            throw new ConditionSyntaxException("path must start with '$.': " + text);
        }
        Cursor cursor = new Cursor(text, 2);
        String root = cursor.identifier();
        List<Object> segments = new ArrayList<>();
        while (!cursor.done()) {
            char c = cursor.next();
            if (c == '.') {
                segments.add(cursor.identifier());
            } else if (c == '[') {
                segments.add(cursor.index());
            } else {
                throw new ConditionSyntaxException("unexpected '" + c + "' in path: " + text);
            }
        }
        return new ValuePath(root, segments);
    }

    /** Navigates {@code rootValue}; returns {@link MissingNode} when any segment is absent. */
    public JsonNode resolve(JsonNode rootValue) {
        JsonNode current = rootValue == null ? MissingNode.getInstance() : rootValue;
        for (Object segment : segments) {
            current = segment instanceof Integer i ? current.path(i) : current.path((String) segment);
        }
        return current;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("$.").append(root);
        segments.forEach(s -> out.append(s instanceof Integer ? "[" + s + "]" : "." + s));
        return out.toString();
    }

    private static final class Cursor {
        private final String text;
        private int pos;

        Cursor(String text, int pos) {
            this.text = text;
            this.pos = pos;
        }

        boolean done() {
            return pos >= text.length();
        }

        char next() {
            return text.charAt(pos++);
        }

        String identifier() {
            int start = pos;
            while (!done() && isIdentifierChar(text.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                throw new ConditionSyntaxException("expected a field name at offset " + start + ": " + text);
            }
            return text.substring(start, pos);
        }

        int index() {
            int start = pos;
            while (!done() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (start == pos || done() || text.charAt(pos) != ']' || pos - start > 6) {
                throw new ConditionSyntaxException("expected '[<index>]' at offset " + start + ": " + text);
            }
            int value = Integer.parseInt(text.substring(start, pos));
            pos++;
            return value;
        }

        private static boolean isIdentifierChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '-';
        }
    }
}
