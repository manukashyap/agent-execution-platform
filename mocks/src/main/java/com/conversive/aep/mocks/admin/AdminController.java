package com.conversive.aep.mocks.admin;

import com.conversive.aep.mocks.payments.PaymentsService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController {

    public record LatencyRequest(String route, long ms) {
    }

    public record FailRateRequest(String route, int percent, Integer status) {
    }

    public record RateLimitRequest(String route, int count, int retryAfterS) {
    }

    public record DropConnectionRequest(String route, int count) {
    }

    private final MockControls controls;
    private final PaymentsService payments;
    private final List<Resettable> resettables;

    public AdminController(MockControls controls, PaymentsService payments, List<Resettable> resettables) {
        this.controls = controls;
        this.payments = payments;
        this.resettables = resettables;
    }

    @PostMapping("/latency")
    public Map<String, Long> setLatency(@RequestBody LatencyRequest request) {
        controls.setLatency(request.route(), request.ms());
        return controls.latencies();
    }

    @GetMapping("/latency")
    public Map<String, Long> latency() {
        return controls.latencies();
    }

    @PostMapping("/fail-rate")
    public Map<String, MockControls.FailRate> setFailRate(@RequestBody FailRateRequest request) {
        controls.setFailRate(request.route(), request.percent(), request.status());
        return controls.failRates();
    }

    @GetMapping("/fail-rate")
    public Map<String, MockControls.FailRate> failRate() {
        return controls.failRates();
    }

    @PostMapping("/rate-limit")
    public ResponseEntity<Void> setRateLimit(@RequestBody RateLimitRequest request) {
        controls.setRateLimit(request.route(), request.count(), request.retryAfterS());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/drop-connection")
    public ResponseEntity<Void> setDropConnection(@RequestBody DropConnectionRequest request) {
        controls.setDropConnection(request.route(), request.count());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/calls")
    public Map<String, Long> calls() {
        return controls.calls();
    }

    @GetMapping("/calls/payments")
    public Map<String, Object> paymentCalls() {
        return Map.of("charges", payments.executedCharges(), "refunds", payments.executedRefunds());
    }

    @GetMapping("/calls/{route}")
    public Map<String, Long> calls(@PathVariable String route) {
        return Map.of(route, controls.calls(route));
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        resettables.forEach(Resettable::reset);
        return ResponseEntity.noContent().build();
    }
}
