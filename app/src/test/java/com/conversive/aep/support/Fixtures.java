package com.conversive.aep.support;

import com.conversive.aep.definition.model.DefinitionCodec;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class Fixtures {

    private Fixtures() {
    }

    public static String read(String path) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("missing fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String pdfExampleJson() {
        return read("pdf-example.json");
    }

    public static JsonNode pdfExample() {
        return DefinitionCodec.readTree(pdfExampleJson());
    }
}
