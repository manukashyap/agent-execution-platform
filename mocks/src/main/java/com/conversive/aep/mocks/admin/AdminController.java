package com.conversive.aep.mocks.admin;

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

    public record FailRateRequest(String route, int percent) {
    }

    private final MockControls controls;

    public AdminController(MockControls controls) {
        this.controls = controls;
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
    public Map<String, Integer> setFailRate(@RequestBody FailRateRequest request) {
        controls.setFailRate(request.route(), request.percent());
        return controls.failRates();
    }

    @GetMapping("/fail-rate")
    public Map<String, Integer> failRate() {
        return controls.failRates();
    }

    @GetMapping("/calls")
    public Map<String, Long> calls() {
        return controls.calls();
    }

    @GetMapping("/calls/{route}")
    public Map<String, Long> calls(@PathVariable String route) {
        return Map.of(route, controls.calls(route));
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        controls.reset();
        return ResponseEntity.noContent().build();
    }
}
