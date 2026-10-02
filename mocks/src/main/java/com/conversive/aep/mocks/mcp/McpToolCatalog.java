package com.conversive.aep.mocks.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tool names, descriptions and input schemas advertised by {@code tools/list}. */
final class McpToolCatalog {

    private McpToolCatalog() {
    }

    static List<Map<String, Object>> tools() {
        return List.of(
                tool("payments.charge", "Charge a customer (idempotent via Idempotency-Key).",
                        schema(Map.of("customer_id", "string", "amount_cents", "integer", "currency", "string"),
                                List.of("customer_id", "amount_cents", "currency"))),
                tool("payments.refund", "Refund a charge (idempotent via Idempotency-Key).",
                        schema(Map.of("charge_id", "string"), List.of("charge_id"))),
                tool("messaging.send", "Send a message (not idempotent).",
                        schema(Map.of("to", "string", "body", "string"), List.of("to", "body"))),
                tool("crm.upsert", "Create or update a CRM contact by external_ref; the Idempotency-Key is stored on the record and the result says whether it was created.",
                        schema(Map.of("external_ref", "string", "name", "string", "email", "string", "label", "string"),
                                List.of("external_ref", "name", "email"))),
                tool("crm.get", "Look up CRM contacts by external reference; optional effect_key limits it to contacts written by that effect.",
                        schema(Map.of("external_ref", "string", "effect_key", "string"), List.of("external_ref"))),
                tool("crm.delete", "Delete the CRM contact with an external reference, optionally only if written by effect_key (inverse of crm.upsert).",
                        schema(Map.of("external_ref", "string", "effect_key", "string"), List.of("external_ref"))),
                tool("leads.fetch", "Fetch leads (read-only).",
                        schema(Map.of("limit", "integer"), List.of())));
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("description", description);
        out.put("inputSchema", inputSchema);
        return out;
    }

    private static Map<String, Object> schema(Map<String, String> propertyTypes, List<String> required) {
        Map<String, Object> properties = new LinkedHashMap<>();
        propertyTypes.keySet().stream().sorted()
                .forEach(k -> properties.put(k, Map.of("type", propertyTypes.get(k))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("properties", properties);
        out.put("required", required);
        return out;
    }
}
