package com.conversive.aep.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** Optional capability of a {@link NodeExecutor}: ask the provider whether a forward call already took effect. */
public interface ForwardLookup {

    /** The effect found for the forward call described by {@code ctx}, or empty when none exists. */
    Optional<JsonNode> lookupForward(NodeContext ctx);
}
