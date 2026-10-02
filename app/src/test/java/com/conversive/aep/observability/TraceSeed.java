package com.conversive.aep.observability;

import com.conversive.aep.common.TenantId;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rows of one finished {@code lead_enrichment} run, written straight into the V1–V5 tables: fetch_leads retried
 * once, classify_leads fell back from vllm to llm-b, update_crm and send_message each made one tool call.
 */
final class TraceSeed {

    static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");
    private static final String SHA = "a".repeat(64);
    private static final String CANDIDATES = "[{\"provider\":\"vllm\",\"score\":0.9},{\"provider\":\"llm-b\",\"score\":0.7}]";

    private final JdbcClient jdbc;
    private final TenantId tenant;
    private final String definitionJson;
    private final UUID executionId = UUID.randomUUID();

    TraceSeed(JdbcClient jdbc, TenantId tenant, String definitionJson) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.definitionJson = definitionJson;
    }

    UUID executionId() {
        return executionId;
    }

    TenantId tenant() {
        return tenant;
    }

    void leadEnrichmentRun() {
        execution();
        nodeRun("fetch_leads", 1, "FAILED", "UPSTREAM_TIMEOUT", 0, 2000);
        nodeRun("fetch_leads", 2, "SUCCEEDED", null, 3000, 3400);
        nodeRun("classify_leads", 1, "SUCCEEDED", null, 3500, 5500);
        nodeRun("update_crm", 1, "SUCCEEDED", null, 5600, 6000);
        nodeRun("send_message", 1, "SUCCEEDED", null, 6100, 6500);
        llmCall(tenant, 0, "vllm", "vllm-default", "best_score", 0, 0, "0", 300, "UPSTREAM_UNAVAILABLE", 3800);
        llmCall(tenant, 1, "llm-b", "model-b", "fallback_after_error", 420, 80, "0.002000", 1500, null, 5400);
        toolCall("update_crm", "crm.upsert", 120, effectKey('1'), 5900);
        toolCall("send_message", "messaging.send", 200, effectKey('2'), 6400);
        ledger("update_crm", effectKey('1'), "NATIVE_KEY", "crm_42");
        ledger("send_message", effectKey('2'), "NONE", null);
        budget();
    }

    /** Same execution id under another tenant: must never show in this tenant's trace. */
    void strayLlmCallOf(TenantId other) {
        llmCall(other, 0, "llm-a", "model-a", "best_score", 9999, 9999, "9.000000", 10, null, 1000);
    }

    private void execution() {
        jdbc.sql("""
                INSERT INTO workflow_definition (tenant_id, workflow_id, def_version, spec, sha256)
                VALUES (:t, 'lead_enrichment', 1, CAST(:spec AS jsonb), :sha)""")
                .param("t", tenant.value()).param("spec", definitionJson).param("sha", SHA).update();
        jdbc.sql("""
                INSERT INTO workflow_execution (id, tenant_id, workflow_id, def_version, mode, status,
                                                started_at, ended_at, created_at)
                VALUES (:id, :t, 'lead_enrichment', 1, 'LIVE', 'SUCCEEDED', :start, :end, :start)""")
                .param("id", executionId).param("t", tenant.value())
                .param("start", at(0)).param("end", at(9000)).update();
    }

    private void nodeRun(String node, int attempt, String status, String error, long startMs, long endMs) {
        jdbc.sql("""
                INSERT INTO node_run (tenant_id, execution_id, node_id, call_index, phase, attempt, status,
                                      error_code, started_at, ended_at)
                VALUES (:t, :e, :n, 0, 'FORWARD', :a, :s, :err, :start, :end)""")
                .param("t", tenant.value()).param("e", executionId).param("n", node).param("a", attempt)
                .param("s", status).param("err", error).param("start", at(startMs)).param("end", at(endMs))
                .update();
    }

    private void llmCall(TenantId owner, int seq, String provider, String model, String reason, int prompt,
                         int completion, String cost, long latency, String error, long atMs) {
        jdbc.sql("""
                INSERT INTO llm_call (tenant_id, execution_id, node_id, call_index, attempt, turn, seq, provider,
                                      model, priority, candidates, reason, prompt_tokens, completion_tokens,
                                      cost_usd, latency_ms, outcome, error_code, created_at)
                VALUES (:t, :e, 'classify_leads', 0, 1, 0, :seq, :p, :m, 'NORMAL', CAST(:c AS jsonb), :r,
                        :pt, :ct, :cost, :lat, :o, :err, :at)""")
                .param("t", owner.value()).param("e", executionId).param("seq", seq).param("p", provider)
                .param("m", model).param("c", CANDIDATES).param("r", reason).param("pt", prompt)
                .param("ct", completion).param("cost", new BigDecimal(cost)).param("lat", latency)
                .param("o", error == null ? "SUCCEEDED" : "FAILED").param("err", error).param("at", at(atMs))
                .update();
    }

    private void toolCall(String node, String tool, long latency, String effectKey, long atMs) {
        jdbc.sql("""
                INSERT INTO tool_call_audit (tenant_id, execution_id, node_id, call_index, phase, attempt,
                                             tool_name, args_sha256, outcome, latency_ms, effect_key, created_at)
                VALUES (:t, :e, :n, 0, 'FORWARD', 1, :tool, :sha, 'SUCCEEDED', :lat, :k, :at)""")
                .param("t", tenant.value()).param("e", executionId).param("n", node).param("tool", tool)
                .param("sha", SHA).param("lat", latency).param("k", effectKey).param("at", at(atMs)).update();
    }

    private void ledger(String node, String effectKey, String mode, String externalRef) {
        jdbc.sql("""
                INSERT INTO side_effect_ledger (effect_key, tenant_id, execution_id, node_id, phase, call_index,
                                                state, idempotency_mode, owner_attempt, lease_until, external_ref)
                VALUES (:k, :t, :e, :n, 'FORWARD', 0, 'COMMITTED', :mode, 1, :lease, :ref)""")
                .param("k", effectKey).param("t", tenant.value()).param("e", executionId).param("n", node)
                .param("mode", mode).param("lease", at(60_000)).param("ref", externalRef).update();
    }

    private void budget() {
        jdbc.sql("""
                INSERT INTO execution_budget (tenant_id, execution_id, limit_usd, reserved_usd, spent_usd)
                VALUES (:t, :e, 5, 0, 0.002)""")
                .param("t", tenant.value()).param("e", executionId).update();
        reservation("llm:classify_leads:0:1:0:0", "0.003000", null, "CANCELLED");
        reservation("llm:classify_leads:0:1:0:1", "0.002500", "0.002000", "CONFIRMED");
    }

    private void reservation(String ref, String amount, String actual, String status) {
        jdbc.sql("""
                INSERT INTO budget_reservation (id, tenant_id, execution_id, node_id, call_index, ref,
                                                amount_usd, actual_usd, status)
                VALUES (:id, :t, :e, 'classify_leads', 0, :ref, :amount, :actual, :s)""")
                .param("id", UUID.randomUUID()).param("t", tenant.value()).param("e", executionId)
                .param("ref", ref).param("amount", new BigDecimal(amount))
                .param("actual", actual == null ? null : new BigDecimal(actual)).param("s", status).update();
    }

    private String effectKey(char c) {
        return executionId.toString().replace("-", "").substring(0, 32) + String.valueOf(c).repeat(32);
    }

    private static Timestamp at(long offsetMs) {
        return Timestamp.from(T0.plusMillis(offsetMs));
    }
}
