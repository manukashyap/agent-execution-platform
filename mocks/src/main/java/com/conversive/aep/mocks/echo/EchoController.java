package com.conversive.aep.mocks.echo;

import com.conversive.aep.mocks.admin.MockControls;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Placeholder route proving the admin knobs; real service mocks arrive in P3–P5. */
@RestController
public class EchoController {

    static final String ROUTE = "echo";

    private final MockControls controls;

    public EchoController(MockControls controls) {
        this.controls = controls;
    }

    @GetMapping("/echo")
    public Map<String, Object> get(@RequestHeader(value = "Idempotency-Key", required = false) String key) {
        controls.enter(ROUTE);
        return response(key, null);
    }

    @PostMapping("/echo")
    public Map<String, Object> post(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @RequestBody(required = false) JsonNode body) {
        controls.enter(ROUTE);
        return response(key, body);
    }

    private static Map<String, Object> response(String key, JsonNode body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("route", ROUTE);
        out.put("idempotencyKey", key);
        out.put("body", body);
        return out;
    }
}
