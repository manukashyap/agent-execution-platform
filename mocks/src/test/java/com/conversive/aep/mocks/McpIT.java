package com.conversive.aep.mocks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpIT extends MockIT {

    private Reply rpc(String method, Object params, Map<String, String> headers) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("jsonrpc", "2.0", "id", 7, "method", method));
        if (params != null) {
            body.put("params", params);
        }
        return post("/mcp", body, headers);
    }

    private Reply call(String tool, Map<String, Object> arguments, Map<String, String> headers) throws Exception {
        return rpc("tools/call", Map.of("name", tool, "arguments", arguments), headers);
    }

    @Test
    void toolsListReturnsSixToolsWithSchemas() throws Exception {
        JsonNode tools = rpc("tools/list", null, Map.of()).body().get("result").get("tools");

        List<String> names = new ArrayList<>();
        tools.forEach(t -> {
            names.add(t.get("name").asText());
            assertThat(t.get("description").asText()).isNotBlank();
            assertThat(t.get("inputSchema").get("type").asText()).isEqualTo("object");
        });
        assertThat(names).containsExactlyInAnyOrder("payments.charge", "payments.refund", "messaging.send",
                "crm.upsert", "crm.get", "leads.fetch");
    }

    @Test
    void echoesRequestIdAndRejectsUnknownMethodAndTool() throws Exception {
        Reply unknownMethod = rpc("nope", null, Map.of());
        assertThat(unknownMethod.body().get("id").asInt()).isEqualTo(7);
        assertThat(unknownMethod.body().get("error").get("code").asInt()).isEqualTo(-32601);

        Reply unknownTool = call("nope.tool", Map.of(), Map.of());
        assertThat(unknownTool.body().get("error").get("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void invalidParamsAreJsonRpcErrors() throws Exception {
        assertThat(rpc("tools/call", Map.of("arguments", Map.of()), Map.of()).body().get("error").get("code").asInt())
                .isEqualTo(-32602);
        assertThat(call("crm.get", Map.of(), Map.of()).body().get("error").get("code").asInt()).isEqualTo(-32602);
        assertThat(call("crm.upsert", Map.of("name", "x"), Map.of()).body().get("error").get("code").asInt())
                .isEqualTo(-32602);
    }

    @Test
    void crmLeadsAndMessagingRoundTrip() throws Exception {
        JsonNode created = call("crm.upsert", Map.of("external_ref", "m-1", "name", "Ada", "email", "a@x.test"), Map.of())
                .body().get("result");
        assertThat(created.get("isError").asBoolean()).isFalse();
        assertThat(created.get("content").get(0).get("type").asText()).isEqualTo("json");
        assertThat(created.get("content").get(0).get("json").get("contact_id").asText()).startsWith("ct_");

        JsonNode found = call("crm.get", Map.of("external_ref", "m-1"), Map.of()).body().get("result");
        assertThat(found.get("content").get(0).get("json").get("contacts")).hasSize(1);

        JsonNode leads = call("leads.fetch", Map.of("limit", 2), Map.of()).body().get("result");
        assertThat(leads.get("content").get(0).get("json").get("leads")).hasSize(2);

        JsonNode sent = call("messaging.send", Map.of("to", "a@b.test", "body", "hi"), Map.of()).body().get("result");
        assertThat(sent.get("content").get(0).get("json").get("message_id").asText()).startsWith("msg_");
        assertThat(get("/admin/calls").body().get("mcp").asLong()).isEqualTo(4);
    }

    @Test
    void idempotentChargeThroughMcpUsesForwardedHeader() throws Exception {
        Map<String, Object> args = Map.of("customer_id", "c-1", "amount_cents", 500, "currency", "USD");
        Map<String, String> key = Map.of("Idempotency-Key", "mcp-k1");

        JsonNode first = call("payments.charge", args, key).body().get("result");
        JsonNode second = call("payments.charge", args, key).body().get("result");

        assertThat(first.get("isError").asBoolean()).isFalse();
        assertThat(second.get("content").get(0).get("json")).isEqualTo(first.get("content").get(0).get("json"));
        assertThat(get("/admin/calls/payments").body().get("charges")).hasSize(1);

        String chargeId = first.get("content").get(0).get("json").get("charge_id").asText();
        JsonNode refund = call("payments.refund", Map.of("charge_id", chargeId), Map.of("Idempotency-Key", "mcp-r1"))
                .body().get("result");
        assertThat(refund.get("content").get(0).get("json").get("status").asText()).isEqualTo("refunded");
    }

    @Test
    void toolFailuresAreIsErrorResults() throws Exception {
        Map<String, Object> args = Map.of("customer_id", "c-1", "amount_cents", 500, "currency", "USD");

        JsonNode noKey = call("payments.charge", args, Map.of()).body().get("result");
        assertThat(noKey.get("isError").asBoolean()).isTrue();
        assertThat(noKey.get("content").get(0).get("json").get("status").asInt()).isEqualTo(400);

        post("/admin/fail-rate", Map.of("route", "messaging.send", "percent", 100));
        JsonNode failed = call("messaging.send", Map.of("to", "a", "body", "b"), Map.of()).body().get("result");
        assertThat(failed.get("isError").asBoolean()).isTrue();
        assertThat(failed.get("content").get(0).get("json").get("status").asInt()).isEqualTo(500);
    }

    @Test
    void rateLimitOnMcpRouteIs429() throws Exception {
        post("/admin/rate-limit", Map.of("route", "mcp", "count", 1, "retryAfterS", 3));

        Reply limited = rpc("tools/list", null, Map.of());

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.header("Retry-After")).isEqualTo("3");
    }
}
