package com.conversive.aep.observability;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.tenancy.TenantTier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns every platform meter name and tag (06 §4.13). Labels are limited to {@code tenant_tier, workflow_id,
 * node_type, provider, model} plus a bounded status/outcome/reason; a tenant id is never a label (10 k tenants):
 * per-tenant questions go through {@code /trace}. Recording never throws into the business path.
 */
public class AepMetrics {

    // The 9 PDF metrics (Prometheus names after unit/_total suffixing in comments).
    /** {@code workflow_execution_latency_seconds{tenant_tier,workflow_id,status}} */
    public static final String WORKFLOW_EXECUTION_LATENCY = "workflow.execution.latency";
    /** {@code workflow_executions_total{tenant_tier,workflow_id,status}}: success rate = SUCCEEDED / all. */
    public static final String WORKFLOW_EXECUTIONS = "workflow.executions";
    /** {@code node_execution_latency_seconds{tenant_tier,workflow_id,node_type,status}} */
    public static final String NODE_EXECUTION_LATENCY = "node.execution.latency";
    /** {@code llm_latency_seconds{tenant_tier,provider,model,outcome}} */
    public static final String LLM_LATENCY = "llm.latency";
    /** {@code llm_tokens_total{tenant_tier,provider,model,direction}} */
    public static final String LLM_TOKENS = "llm.tokens";
    /** {@code llm_cost_usd_total{tenant_tier,provider,model}} */
    public static final String LLM_COST_USD = "llm.cost.usd";
    /** {@code node_retries_total{tenant_tier,workflow_id,node_type}} */
    public static final String NODE_RETRIES = "node.retries";
    /** {@code queue_depth{source}}: Temporal backlog per task-queue type plus admitted-but-QUEUED executions. */
    public static final String QUEUE_DEPTH = "queue.depth";
    /** {@code provider_errors_total{provider,error_class}}: LLM providers and tool upstreams. */
    public static final String PROVIDER_ERRORS = "provider.errors";

    // Extras named in 06 §4.13 and supporting meters.
    public static final String COMPENSATIONS = "compensations";
    public static final String ROUTER_DECISIONS = "router.decisions";
    public static final String SIDE_EFFECT_UNKNOWN = "side.effect.unknown";
    /** Temporal activity schedule-to-start (complements the backlog gauge). */
    public static final String SCHEDULE_TO_START = "schedule.to.start";
    public static final String ADMISSION_REJECTIONS = "admission.rejections";
    public static final String BUDGET_REJECTIONS = "budget.rejections";
    public static final String TOOL_CALL_LATENCY = "tool.call.latency";

    public static final String TAG_TENANT_TIER = "tenant_tier";
    public static final String TAG_WORKFLOW_ID = "workflow_id";
    public static final String TAG_NODE_TYPE = "node_type";
    public static final String TAG_PROVIDER = "provider";
    public static final String TAG_MODEL = "model";
    public static final String TAG_STATUS = "status";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_REASON = "reason";
    public static final String TAG_DIRECTION = "direction";
    public static final String TAG_ERROR_CLASS = "error_class";
    public static final String TAG_SOURCE = "source";

    public static final String DIRECTION_PROMPT = "prompt";
    public static final String DIRECTION_COMPLETION = "completion";
    public static final String OUTCOME_SUCCEEDED = "SUCCEEDED";
    public static final String OUTCOME_FAILED = "FAILED";
    public static final String SOURCE_TEMPORAL_WORKFLOW = "temporal_workflow";
    public static final String SOURCE_TEMPORAL_ACTIVITY = "temporal_activity";
    public static final String SOURCE_ADMISSION_QUEUED = "admission_queued";
    static final String UNKNOWN = "unknown";

    /** Bucket bounds shared by every latency histogram: few enough to keep series per label set small. */
    private static final Duration[] BUCKETS = {
        Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
        Duration.ofSeconds(1), Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10),
        Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofMinutes(30)};
    private static final Logger LOG = LoggerFactory.getLogger(AepMetrics.class);

    private final MeterRegistry registry;
    private final Function<TenantId, TenantTier> tiers;
    private final Map<String, AtomicLong> queueDepths = new ConcurrentHashMap<>();
    private final Counter sideEffectUnknown;

    public AepMetrics(MeterRegistry registry, Function<TenantId, TenantTier> tiers) {
        this.registry = registry;
        this.tiers = tiers;
        this.sideEffectUnknown = Counter.builder(SIDE_EFFECT_UNKNOWN)
                .description("Ledger rows that became UNKNOWN (outcome cannot be reconciled; node NEEDS_ATTENTION)")
                .register(registry);
        for (String source : new String[] {SOURCE_TEMPORAL_WORKFLOW, SOURCE_TEMPORAL_ACTIVITY, SOURCE_ADMISSION_QUEUED}) {
            AtomicLong depth = new AtomicLong();
            queueDepths.put(source, depth);
            Gauge.builder(QUEUE_DEPTH, depth, AtomicLong::get)
                    .description("Approximate backlog: Temporal task queue per type, and executions still QUEUED")
                    .tag(TAG_SOURCE, source)
                    .register(registry);
        }
    }

    /** Engine: an execution reached a terminal status. */
    public void executionCompleted(TenantId tenant, String workflowId, String status, Duration latency) {
        String tier = tier(tenant);
        timer(WORKFLOW_EXECUTION_LATENCY, TAG_TENANT_TIER, tier, TAG_WORKFLOW_ID, label(workflowId),
                TAG_STATUS, label(status)).record(latency);
        Counter.builder(WORKFLOW_EXECUTIONS)
                .tags(TAG_TENANT_TIER, tier, TAG_WORKFLOW_ID, label(workflowId), TAG_STATUS, label(status))
                .register(registry).increment();
    }

    /** Engine: a node (one call index, one phase) reached a terminal status after all its attempts. */
    public void nodeCompleted(TenantId tenant, String workflowId, String nodeType, String status, Duration latency) {
        timer(NODE_EXECUTION_LATENCY, TAG_TENANT_TIER, tier(tenant), TAG_WORKFLOW_ID, label(workflowId),
                TAG_NODE_TYPE, label(nodeType), TAG_STATUS, label(status)).record(latency);
    }

    /** Engine: a node attempt failed and Temporal will retry it (call once per retry, i.e. attempt ≥ 2 starts). */
    public void nodeRetried(TenantId tenant, String workflowId, String nodeType) {
        Counter.builder(NODE_RETRIES)
                .tags(TAG_TENANT_TIER, tier(tenant), TAG_WORKFLOW_ID, label(workflowId), TAG_NODE_TYPE, label(nodeType))
                .register(registry).increment();
    }

    /** Engine: one compensation step finished; outcome e.g. COMPENSATED, SKIPPED, FAILED, NEEDS_ATTENTION. */
    public void compensation(String outcome) {
        Counter.builder(COMPENSATIONS).tag(TAG_OUTCOME, label(outcome)).register(registry).increment();
    }

    /** Engine (activity entry): time from the attempt being scheduled to a worker starting it. */
    public void scheduleToStart(TenantId tenant, Duration latency) {
        timer(SCHEDULE_TO_START, TAG_TENANT_TIER, tier(tenant)).record(latency);
    }

    public void routerDecision(String provider, String reason) {
        Counter.builder(ROUTER_DECISIONS).tags(TAG_PROVIDER, label(provider), TAG_REASON, label(reason))
                .register(registry).increment();
    }

    /** One LLM provider call: latency, tokens and cost; a non-null {@code errorCode} also counts a provider error. */
    public void llmCall(TenantId tenant, String provider, String model, int promptTokens, int completionTokens,
            BigDecimal costUsd, Duration latency, String errorCode) {
        String tier = tier(tenant);
        String outcome = errorCode == null ? OUTCOME_SUCCEEDED : OUTCOME_FAILED;
        timer(LLM_LATENCY, TAG_TENANT_TIER, tier, TAG_PROVIDER, label(provider), TAG_MODEL, label(model),
                TAG_OUTCOME, outcome).record(latency);
        llmTokens(tier, provider, model, DIRECTION_PROMPT, promptTokens);
        llmTokens(tier, provider, model, DIRECTION_COMPLETION, completionTokens);
        Counter.builder(LLM_COST_USD)
                .tags(TAG_TENANT_TIER, tier, TAG_PROVIDER, label(provider), TAG_MODEL, label(model))
                .register(registry).increment(costUsd == null ? 0 : costUsd.doubleValue());
        if (errorCode != null) {
            providerError(provider, errorCode);
        }
    }

    public void providerError(String provider, String errorClass) {
        Counter.builder(PROVIDER_ERRORS).tags(TAG_PROVIDER, label(provider), TAG_ERROR_CLASS, label(errorClass))
                .register(registry).increment();
    }

    /** One tool-gateway attempt; the tool name is the {@code provider} label (bounded by the registry). */
    public void toolCall(TenantId tenant, String tool, String outcome, String errorCode, Duration latency) {
        timer(TOOL_CALL_LATENCY, TAG_TENANT_TIER, tier(tenant), TAG_PROVIDER, label(tool), TAG_OUTCOME, label(outcome))
                .record(latency);
        if (errorCode != null) {
            providerError(tool, errorCode);
        }
    }

    public void sideEffectUnknown() {
        sideEffectUnknown.increment();
    }

    /** reason = the limit code, e.g. RATE_LIMITED or CONCURRENCY_LIMIT. */
    public void admissionRejected(TenantId tenant, String reason) {
        Counter.builder(ADMISSION_REJECTIONS).tags(TAG_TENANT_TIER, tier(tenant), TAG_REASON, label(reason))
                .register(registry).increment();
    }

    /** reason = which budget refused the reservation, e.g. execution_cap or tenant_budget. */
    public void budgetRejected(TenantId tenant, String reason) {
        Counter.builder(BUDGET_REJECTIONS).tags(TAG_TENANT_TIER, tier(tenant), TAG_REASON, label(reason))
                .register(registry).increment();
    }

    public void queueDepth(String source, long depth) {
        AtomicLong gauge = queueDepths.get(source);
        if (gauge == null) {
            throw new IllegalArgumentException("unknown queue_depth source " + source);
        }
        gauge.set(depth);
    }

    private void llmTokens(String tier, String provider, String model, String direction, int tokens) {
        Counter.builder(LLM_TOKENS)
                .tags(TAG_TENANT_TIER, tier, TAG_PROVIDER, label(provider), TAG_MODEL, label(model),
                        TAG_DIRECTION, direction)
                .register(registry).increment(Math.max(0, tokens));
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).serviceLevelObjectives(BUCKETS).register(registry);
    }

    private String tier(TenantId tenant) {
        if (tenant == null) {
            return UNKNOWN;
        }
        try {
            return tiers.apply(tenant).name();
        } catch (RuntimeException e) {
            LOG.debug("tenant tier lookup failed; tagging metric tenant_tier=unknown", e);
            return UNKNOWN;
        }
    }

    private static String label(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
