package com.conversive.aep.mocks.llm;

import com.conversive.aep.mocks.admin.MockControls;
import com.conversive.aep.mocks.support.MockHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LlmController {

    private static final Set<String> PROVIDERS = Set.of("llm-a", "llm-b", "vllm");

    private final MockControls controls;
    private final LlmResponder responder;

    public LlmController(MockControls controls, LlmResponder responder) {
        this.controls = controls;
        this.responder = responder;
    }

    @PostMapping("/llm/{provider}/v1/chat/completions")
    public Map<String, Object> chatCompletions(@PathVariable String provider, @RequestBody JsonNode request) {
        if (!PROVIDERS.contains(provider)) {
            throw new MockHttpException(404, "unknown_provider");
        }
        controls.enter(provider);
        return responder.respond(request);
    }
}
