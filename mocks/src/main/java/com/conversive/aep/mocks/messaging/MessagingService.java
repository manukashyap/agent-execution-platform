package com.conversive.aep.mocks.messaging;

import com.conversive.aep.mocks.admin.MockControls;
import com.conversive.aep.mocks.admin.Resettable;
import com.conversive.aep.mocks.support.MockHttpException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/** Pivot, non-idempotent messaging: every send yields a fresh message id. */
@Service
public class MessagingService implements Resettable {

    public static final String ROUTE = "messaging.send";

    public record SendRequest(String to, String body) {
    }

    private final MockControls controls;
    private final AtomicLong seq = new AtomicLong();

    public MessagingService(MockControls controls) {
        this.controls = controls;
    }

    public Map<String, Object> send(SendRequest request) {
        controls.enter(ROUTE);
        if (request.to() == null || request.to().isBlank() || request.body() == null) {
            throw new MockHttpException(400, "invalid_request");
        }
        return Map.of("message_id", "msg_%06d".formatted(seq.incrementAndGet()));
    }

    @Override
    public void reset() {
        seq.set(0);
    }
}
