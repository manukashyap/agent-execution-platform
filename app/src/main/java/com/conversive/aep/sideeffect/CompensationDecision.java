package com.conversive.aep.sideeffect;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * What the saga should do for one forward effect (06 §4.9, reconcile-then-compensate).
 * {@code forwardResponse} is set only for {@link Kind#COMPENSATE} and {@link Kind#PIVOT_EXECUTED}.
 */
public record CompensationDecision(Kind kind, JsonNode forwardResponse, String reason) {

    public enum Kind {
        /** The forward effect happened and has an inverse: run it via the guard with {@code Phase.COMPENSATE}. */
        COMPENSATE,
        /** Nothing to undo: the forward effect never happened, or the tool is RETRIABLE/READ_ONLY. */
        SKIP,
        /** NATIVE_KEY forward with an unresolved outcome: re-run the forward through the guard, then decide again. */
        RECONCILE_FORWARD,
        /** The outcome cannot be determined (mode NONE): a human must check the provider. No call was made. */
        NEEDS_ATTENTION,
        /** A pivot (irreversible) effect happened: the saga cannot roll back past it. */
        PIVOT_EXECUTED
    }

    public CompensationDecision {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
    }

    static CompensationDecision of(Kind kind, String reason) {
        return new CompensationDecision(kind, null, reason);
    }
}
