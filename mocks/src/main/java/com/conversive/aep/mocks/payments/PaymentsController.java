package com.conversive.aep.mocks.payments;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentsController {

    private static final String KEY_HEADER = "Idempotency-Key";

    private final PaymentsService payments;

    public PaymentsController(PaymentsService payments) {
        this.payments = payments;
    }

    @PostMapping("/charge")
    public Map<String, Object> charge(@RequestHeader(value = KEY_HEADER, required = false) String key,
                                      @RequestBody PaymentsService.ChargeRequest request) {
        return payments.charge(key, request);
    }

    @PostMapping("/refund")
    public Map<String, Object> refund(@RequestHeader(value = KEY_HEADER, required = false) String key,
                                      @RequestBody PaymentsService.RefundRequest request) {
        return payments.refund(key, request);
    }

    @GetMapping("/charges/{chargeId}")
    public Map<String, Object> charge(@PathVariable String chargeId) {
        return payments.getCharge(chargeId);
    }

    @GetMapping("/charges")
    public Map<String, Object> byKey(@RequestParam("idempotency_key") String key) {
        return payments.findByIdempotencyKey(key);
    }
}
