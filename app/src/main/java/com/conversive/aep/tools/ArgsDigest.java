package com.conversive.aep.tools;

import com.conversive.aep.common.Hashing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** SHA-256 of the canonical (key-sorted) JSON of tool args, so the audit can correlate calls without storing them. */
public final class ArgsDigest {

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private ArgsDigest() {
    }

    public static String sha256(JsonNode args) {
        try {
            Object plain = CANONICAL.treeToValue(args, Object.class);
            return Hashing.sha256Hex(CANONICAL.writeValueAsString(plain));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("tool args are not serialisable", e);
        }
    }
}
