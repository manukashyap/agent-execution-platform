package com.conversive.aep.common.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record OutboundResponse(int status, Map<String, List<String>> headers, JsonNode body) {

    public OutboundResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public Optional<String> header(String name) {
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty())
                .map(e -> e.getValue().get(0))
                .findFirst();
    }
}
