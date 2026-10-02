package com.conversive.aep.router.web;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.router.LlmMessage;
import com.conversive.aep.router.LlmRequest;
import com.conversive.aep.router.LlmResponse;
import com.conversive.aep.router.LlmRouter;
import com.conversive.aep.router.ProviderHealthView;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only router driver for {@code scripts/vllm-degradation.sh} until the engine runs llm nodes.
 * Calls are recorded in {@code llm_call} under a fresh execution id and node {@code debug}.
 */
@RestController
@Profile("dev")
@RequestMapping("/internal/router")
public class RouterDebugController {

    private static final int MAX_PROMPT_CHARS = 10_000;
    private static final long MAX_TIMEOUT_MS = 60_000;
    private static final long DEFAULT_TIMEOUT_MS = 15_000;

    private final LlmRouter router;

    public RouterDebugController(LlmRouter router) {
        this.router = router;
    }

    public record CompleteRequest(String tenantId, String prompt, Priority priority, Set<String> capabilities,
            Long timeoutMs) {
    }

    public record CompleteResponse(String provider, String model, String reason, String text,
            List<LlmResponse.ProviderAttempt> attempts) {
    }

    public record State(List<ProviderHealthView> providers, Map<String, Map<String, Long>> decisionsLast10s) {
    }

    @PostMapping("/complete")
    public ResponseEntity<?> complete(@RequestBody CompleteRequest body) {
        if (body == null || body.prompt() == null || body.prompt().isBlank() || body.prompt().length() > MAX_PROMPT_CHARS) {
            return error(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "prompt is required (max 10000 chars)");
        }
        try {
            LlmResponse r = router.route(toRequest(body));
            return ResponseEntity.ok(new CompleteResponse(r.provider(), r.model(), r.reason(), r.content(), r.attempts()));
        } catch (RetryableError e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.code(), e.getMessage());
        } catch (NonRetryableError e) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, e.getMessage());
        }
    }

    @GetMapping("/state")
    public State state() {
        return new State(router.health(), router.recentDecisions().counts());
    }

    private static LlmRequest toRequest(CompleteRequest body) {
        long timeoutMs = body.timeoutMs() == null ? DEFAULT_TIMEOUT_MS : Math.min(Math.max(body.timeoutMs(), 1), MAX_TIMEOUT_MS);
        TenantId tenant = TenantId.of(body.tenantId() == null ? "t_dev" : body.tenantId());
        return new LlmRequest(tenant, ExecutionId.random(), "debug", 0, 1, 0, body.priority(), body.capabilities(),
                List.of(LlmMessage.user(body.prompt())), List.of(), null, Duration.ofMillis(timeoutMs), ExecutionMode.LIVE);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", String.valueOf(message)));
    }
}
