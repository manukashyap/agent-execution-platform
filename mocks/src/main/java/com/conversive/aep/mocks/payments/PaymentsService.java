package com.conversive.aep.mocks.payments;

import com.conversive.aep.mocks.admin.MockControls;
import com.conversive.aep.mocks.admin.Resettable;
import com.conversive.aep.mocks.support.MockHttpException;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Idempotent payments. Processing runs on its own thread, so a charge started by a client that
 * later times out or disconnects still completes server-side after the configured latency.
 */
@Service
public class PaymentsService implements Resettable {

    public static final String CHARGE_ROUTE = "payments.charge";
    public static final String REFUND_ROUTE = "payments.refund";
    public static final String LOOKUP_ROUTE = "payments.lookup";

    public record ChargeRequest(@JsonProperty("customer_id") String customerId,
                                @JsonProperty("amount_cents") Long amountCents,
                                String currency) {
    }

    public record RefundRequest(@JsonProperty("charge_id") String chargeId) {
    }

    private record Charge(String chargeId, String idempotencyKey, ChargeRequest request) {
        Map<String, Object> detail() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("charge_id", chargeId);
            out.put("status", "succeeded");
            out.put("amount_cents", request.amountCents());
            out.put("customer_id", request.customerId());
            out.put("currency", request.currency());
            out.put("idempotency_key", idempotencyKey);
            return out;
        }
    }

    private record Refund(String refundId, String chargeId, String idempotencyKey) {
        Map<String, Object> detail() {
            return Map.of("refund_id", refundId, "charge_id", chargeId, "status", "refunded",
                    "idempotency_key", idempotencyKey);
        }
    }

    private final MockControls controls;
    private final Map<String, CompletableFuture<Map<String, Object>>> byKey = new ConcurrentHashMap<>();
    private final Map<String, Charge> charges = new ConcurrentSkipListMap<>();
    private final Map<String, Refund> refunds = new ConcurrentSkipListMap<>();
    private final AtomicLong chargeSeq = new AtomicLong();
    private final AtomicLong refundSeq = new AtomicLong();

    public PaymentsService(MockControls controls) {
        this.controls = controls;
    }

    public Map<String, Object> charge(String idempotencyKey, ChargeRequest request) {
        controls.admitWithoutLatency(CHARGE_ROUTE);
        requireKey(idempotencyKey);
        if (isBlank(request.customerId()) || isBlank(request.currency())
                || request.amountCents() == null || request.amountCents() <= 0) {
            throw new MockHttpException(400, "invalid_request");
        }
        return idempotent(CHARGE_ROUTE, idempotencyKey, () -> executeCharge(idempotencyKey, request));
    }

    public Map<String, Object> refund(String idempotencyKey, RefundRequest request) {
        controls.admitWithoutLatency(REFUND_ROUTE);
        requireKey(idempotencyKey);
        if (isBlank(request.chargeId())) {
            throw new MockHttpException(400, "invalid_request");
        }
        if (!charges.containsKey(request.chargeId())) {
            throw new MockHttpException(404, "charge_not_found");
        }
        return idempotent(REFUND_ROUTE, idempotencyKey, () -> executeRefund(idempotencyKey, request.chargeId()));
    }

    public Map<String, Object> getCharge(String chargeId) {
        controls.enter(LOOKUP_ROUTE);
        Charge charge = charges.get(chargeId);
        if (charge == null) {
            throw new MockHttpException(404, "charge_not_found");
        }
        return charge.detail();
    }

    public Map<String, Object> findByIdempotencyKey(String idempotencyKey) {
        controls.enter(LOOKUP_ROUTE);
        List<Map<String, Object>> found = charges.values().stream()
                .filter(c -> c.idempotencyKey().equals(idempotencyKey))
                .map(Charge::detail)
                .toList();
        return Map.of("charges", found);
    }

    public List<Map<String, Object>> executedCharges() {
        return new ArrayList<>(charges.values().stream().map(Charge::detail).toList());
    }

    public List<Map<String, Object>> executedRefunds() {
        return new ArrayList<>(refunds.values().stream().map(Refund::detail).toList());
    }

    @Override
    public void reset() {
        byKey.clear();
        charges.clear();
        refunds.clear();
        chargeSeq.set(0);
        refundSeq.set(0);
    }

    private Map<String, Object> executeCharge(String key, ChargeRequest request) {
        Charge charge = new Charge("ch_%06d".formatted(chargeSeq.incrementAndGet()), key, request);
        charges.put(charge.chargeId(), charge);
        return Map.of("charge_id", charge.chargeId(), "status", "succeeded", "amount_cents", request.amountCents());
    }

    private Map<String, Object> executeRefund(String key, String chargeId) {
        Refund refund = new Refund("re_%06d".formatted(refundSeq.incrementAndGet()), chargeId, key);
        refunds.put(refund.refundId(), refund);
        return Map.of("refund_id", refund.refundId(), "charge_id", chargeId, "status", "refunded");
    }

    private Map<String, Object> idempotent(String route, String key, Supplier<Map<String, Object>> work) {
        String scoped = route + ":" + key;
        CompletableFuture<Map<String, Object>> mine = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> existing = byKey.putIfAbsent(scoped, mine);
        if (existing != null) {
            if (existing.isDone() && !existing.isCompletedExceptionally()) {
                return existing.join();
            }
            throw new MockHttpException(409, "in_progress");
        }
        Thread.startVirtualThread(() -> process(route, scoped, mine, work));
        return await(mine);
    }

    private void process(String route, String scoped, CompletableFuture<Map<String, Object>> result,
                         Supplier<Map<String, Object>> work) {
        try {
            controls.applyLatency(route);
            result.complete(work.get());
        } catch (RuntimeException e) {
            byKey.remove(scoped);
            result.completeExceptionally(e);
        }
    }

    private static Map<String, Object> await(CompletableFuture<Map<String, Object>> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static void requireKey(String key) {
        if (isBlank(key)) {
            throw new MockHttpException(400, "idempotency_key_required");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
