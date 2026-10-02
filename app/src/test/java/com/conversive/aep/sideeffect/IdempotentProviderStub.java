package com.conversive.aep.sideeffect;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * WireMock transformer emulating the payments mock contract: the first request with an {@code Idempotency-Key}
 * performs the effect (counted) and answers after {@code processing}; a request with the same key while the
 * first is in flight gets {@code 409 {"error":"in_progress"}}; afterwards it gets the stored response.
 * Uses real time, like a real provider.
 */
final class IdempotentProviderStub implements ResponseDefinitionTransformerV2 {

    static final String NAME = "idempotent-provider";

    private record Effect(String body, Instant completesAt) {
    }

    private final Map<String, Effect> byKey = new HashMap<>();
    private int performed;
    private Duration processing = Duration.ZERO;

    synchronized void reset(Duration newProcessing) {
        byKey.clear();
        performed = 0;
        processing = newProcessing;
    }

    synchronized int performedEffects() {
        return performed;
    }

    synchronized boolean completed(String key) {
        Effect effect = byKey.get(key);
        return effect != null && !Instant.now().isBefore(effect.completesAt());
    }

    void awaitCompleted(String key, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (!completed(key)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("provider never completed effect " + key);
            }
            Thread.sleep(20);
        }
    }

    @Override
    public synchronized ResponseDefinition transform(ServeEvent serveEvent) {
        String key = serveEvent.getRequest().getHeader("Idempotency-Key");
        if (key == null) {
            return json(400, "{\"error\":\"missing_idempotency_key\"}").build();
        }
        Effect existing = byKey.get(key);
        Instant now = Instant.now();
        if (existing == null) {
            performed++;
            String body = "{\"charge_id\":\"ch_" + performed + "\",\"status\":\"succeeded\"}";
            byKey.put(key, new Effect(body, now.plus(processing)));
            return json(200, body).withFixedDelay((int) processing.toMillis()).build();
        }
        if (now.isBefore(existing.completesAt())) {
            return json(409, "{\"error\":\"in_progress\"}").build();
        }
        return json(200, existing.body()).build();
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }

    @Override
    public String getName() {
        return NAME;
    }
}
