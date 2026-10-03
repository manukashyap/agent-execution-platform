package com.conversive.aep.sideeffect;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * The guarded external call. Error contract for {@link #invoke}: throw {@code NonRetryableError} only when the
 * provider definitively did not perform the effect (the ledger row becomes FAILED); throw
 * {@link ProviderInFlightException} (or let a 409 {@code UpstreamClientError} through) when the provider is still
 * processing the same key; anything else is treated as "may have happened".
 */
public interface EffectCall {

    List<String> EXTERNAL_REF_FIELDS = List.of("external_ref", "charge_id", "refund_id", "message_id", "id");

    JsonNode invoke(String idempotencyKey);

    /** For {@code LOOKUP} tools: find an effect that may already have happened under this key. */
    default Optional<JsonNode> lookup() {
        return Optional.empty();
    }

    /** The provider's identifier for the effect, stored in {@code side_effect_ledger.external_ref}. */
    default Optional<String> externalRef(JsonNode response) {
        if (response == null || !response.isObject()) {
            return Optional.empty();
        }
        return EXTERNAL_REF_FIELDS.stream()
                .map(response::get)
                .filter(n -> n != null && n.isValueNode() && !n.isNull())
                .map(JsonNode::asText)
                .findFirst();
    }
}
