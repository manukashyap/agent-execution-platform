package com.conversive.aep.support;

import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.nodes.NodeExecutor;
import com.conversive.aep.nodes.NodeResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only executor for faked node types, driven by the node config:
 * {@code sleepMs}, {@code failTimes} (retryable failures before succeeding), {@code fatal} (non-retryable
 * failure code), {@code output}, {@code costUsd}, {@code tokens}. Records calls and peak concurrency.
 */
public class ScriptedExecutor implements NodeExecutor {

    /** One recorded call. */
    public record CallRecord(String executionId, String nodeId, int callIndex, int attempt, long startedAtNanos,
                             JsonNode input) {
    }

    private final List<CallRecord> calls = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> peak = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "scripted";
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String exec = ctx.executionId().toString();
        calls.add(new CallRecord(exec, ctx.nodeId(), ctx.callIndex(), ctx.attempt(), System.nanoTime(), ctx.input()));
        AtomicInteger current = inFlight.computeIfAbsent(exec, k -> new AtomicInteger());
        int now = current.incrementAndGet();
        peak.computeIfAbsent(exec, k -> new AtomicInteger()).accumulateAndGet(now, Math::max);
        try {
            return run(ctx);
        } finally {
            current.decrementAndGet();
        }
    }

    private NodeResult run(NodeContext ctx) {
        JsonNode config = ctx.config();
        sleep(config.path("sleepMs").asLong(0));
        if (ctx.attempt() <= config.path("failTimes").asInt(0)) {
            throw new RetryableError("SCRIPTED_RETRYABLE", "scripted failure, attempt " + ctx.attempt(),
                    Duration.ofMillis(10));
        }
        if (config.hasNonNull("fatal")) {
            throw new NonRetryableError(config.get("fatal").asText(), "scripted fatal failure");
        }
        JsonNode output = config.has("output") ? config.get("output") : defaultOutput(ctx);
        BigDecimal cost = config.has("costUsd") ? config.get("costUsd").decimalValue() : BigDecimal.ZERO;
        return new NodeResult(output, cost, config.path("tokens").asLong(0), Map.of());
    }

    private static JsonNode defaultOutput(NodeContext ctx) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("node", ctx.nodeId());
        out.put("callIndex", ctx.callIndex());
        if (ctx.input().has("item")) {
            out.set("item", ctx.input().get("item"));
        }
        return out;
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableError("SCRIPTED_INTERRUPTED", "interrupted", null);
        }
    }

    public List<CallRecord> calls(String executionId) {
        return calls.stream().filter(c -> c.executionId().equals(executionId)).toList();
    }

    public int peakConcurrency(String executionId) {
        AtomicInteger p = peak.get(executionId);
        return p == null ? 0 : p.get();
    }
}
