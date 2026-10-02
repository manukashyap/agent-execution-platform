package com.conversive.aep.mocks.mcp;

import com.conversive.aep.mocks.admin.MockControls;
import com.conversive.aep.mocks.crm.CrmService;
import com.conversive.aep.mocks.leads.LeadsService;
import com.conversive.aep.mocks.messaging.MessagingService;
import com.conversive.aep.mocks.payments.PaymentsService;
import com.conversive.aep.mocks.support.MockHttpException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** JSON-RPC 2.0 MCP endpoint delegating to the same service logic as the REST routes. */
@RestController
public class McpController {

    static final String ROUTE = "mcp";
    private static final int INVALID_PARAMS = -32602;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_REQUEST = -32600;

    private final MockControls controls;
    private final ObjectMapper mapper;
    private final PaymentsService payments;
    private final CrmService crm;
    private final LeadsService leads;
    private final MessagingService messaging;

    public McpController(MockControls controls, ObjectMapper mapper, PaymentsService payments, CrmService crm,
                         LeadsService leads, MessagingService messaging) {
        this.controls = controls;
        this.mapper = mapper;
        this.payments = payments;
        this.crm = crm;
        this.leads = leads;
        this.messaging = messaging;
    }

    @PostMapping("/mcp")
    public Map<String, Object> rpc(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                   @RequestBody JsonNode request) {
        controls.enter(ROUTE);
        JsonNode id = request.path("id");
        if (!"2.0".equals(request.path("jsonrpc").asText()) || !request.path("method").isTextual()) {
            return error(id, INVALID_REQUEST, "invalid request");
        }
        return switch (request.path("method").asText()) {
            case "tools/list" -> success(id, Map.of("tools", McpToolCatalog.tools()));
            case "tools/call" -> call(id, key, request.path("params"));
            default -> error(id, METHOD_NOT_FOUND, "method not found: " + request.path("method").asText());
        };
    }

    private Map<String, Object> call(JsonNode id, String key, JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");
        if (name.isEmpty() || !(arguments.isObject() || arguments.isMissingNode())) {
            return error(id, INVALID_PARAMS, "params.name and object params.arguments are required");
        }
        if (McpToolCatalog.tools().stream().noneMatch(t -> t.get("name").equals(name))) {
            return error(id, METHOD_NOT_FOUND, "unknown tool: " + name);
        }
        try {
            Object result = invoke(name, key, arguments);
            return success(id, toolResult(false, result));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return error(id, INVALID_PARAMS, "invalid arguments for " + name);
        } catch (MockHttpException e) {
            if (e.status() == 429) {
                throw e;
            }
            if ("invalid_request".equals(e.getMessage())) {
                return error(id, INVALID_PARAMS, "invalid arguments for " + name);
            }
            Map<String, Object> body = new LinkedHashMap<>(e.body());
            body.put("status", e.status());
            return success(id, toolResult(true, body));
        }
    }

    private Object invoke(String name, String key, JsonNode arguments) throws JsonProcessingException {
        return switch (name) {
            case "payments.charge" -> payments.charge(key, mapper.treeToValue(arguments, PaymentsService.ChargeRequest.class));
            case "payments.refund" -> payments.refund(key, mapper.treeToValue(arguments, PaymentsService.RefundRequest.class));
            case "messaging.send" -> messaging.send(mapper.treeToValue(arguments, MessagingService.SendRequest.class));
            case "crm.upsert" -> crm.create(mapper.treeToValue(arguments, CrmService.ContactRequest.class));
            case "crm.get" -> crm.findByExternalRef(requiredText(arguments, "external_ref"));
            case "crm.delete" -> crm.deleteByExternalRef(requiredText(arguments, "external_ref"));
            default -> leads.fetch(arguments.has("limit") ? arguments.get("limit").asInt() : null);
        };
    }

    private static String requiredText(JsonNode arguments, String field) {
        if (!arguments.path(field).isTextual()) {
            throw new IllegalArgumentException(field + " required");
        }
        return arguments.get(field).asText();
    }

    private Map<String, Object> toolResult(boolean isError, Object payload) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "json");
        content.put("json", mapper.valueToTree(payload));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(content));
        result.put("isError", isError);
        return result;
    }

    private static Map<String, Object> success(JsonNode id, Object result) {
        Map<String, Object> out = envelope(id);
        out.put("result", result);
        return out;
    }

    private static Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> out = envelope(id);
        out.put("error", Map.of("code", code, "message", message));
        return out;
    }

    private static Map<String, Object> envelope(JsonNode id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id.isMissingNode() ? null : id);
        return out;
    }
}
