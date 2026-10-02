package com.conversive.aep.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {

    private Hashing() {
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes; used for effect keys, API key hashes and payload digests. */
    public static String sha256Hex(String raw) {
        return sha256Hex(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
