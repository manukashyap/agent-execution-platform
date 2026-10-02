package com.conversive.aep.engine.workflow;

import com.conversive.aep.definition.model.FrozenDefinition.FrozenNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Progress of one {@code for_each} node: items are started in index order, at most
 * {@code maxConcurrency} at a time; the first item failure stops further starts and the node fails
 * once the items in flight have drained.
 */
final class FanOut {

    private final FrozenNode node;
    private final int total;
    private final int maxConcurrency;
    private final JsonNode[] inlineOutputs;
    private int next;
    private int inFlight;
    private int completed;
    private boolean allInlined = true;
    private String failureCode;
    private String failureMessage;

    FanOut(FrozenNode node, int total, int maxConcurrency) {
        this.node = node;
        this.total = total;
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.inlineOutputs = new JsonNode[total];
    }

    FrozenNode node() {
        return node;
    }

    boolean canStart() {
        return failureCode == null && next < total && inFlight < maxConcurrency;
    }

    int nextIndex() {
        return next;
    }

    void started() {
        next++;
        inFlight++;
    }

    void succeeded(int index, JsonNode inline) {
        inFlight--;
        completed++;
        if (inline == null) {
            allInlined = false;
        } else {
            inlineOutputs[index] = inline;
        }
    }

    void failed(String code, String message) {
        inFlight--;
        if (failureCode == null) {
            failureCode = code;
            failureMessage = message;
        }
    }

    /** Stops further starts (the run is aborting); items in flight still drain. */
    void halt(String code, String message) {
        if (failureCode == null) {
            failureCode = code;
            failureMessage = message;
        }
    }

    boolean settled() {
        return inFlight == 0 && (failureCode != null || completed == total);
    }

    int inFlight() {
        return inFlight;
    }

    String failureCode() {
        return failureCode;
    }

    String failureMessage() {
        return failureMessage;
    }

    /** The array of item outputs when every item was inlined; null otherwise (loaded from the DB). */
    JsonNode inlineArray() {
        if (!allInlined) {
            return null;
        }
        ArrayNode array = JsonNodeFactory.instance.arrayNode(total);
        for (JsonNode out : inlineOutputs) {
            array.add(out);
        }
        return array;
    }
}
