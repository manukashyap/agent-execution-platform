package com.conversive.aep.sideeffect.persistence;

import com.conversive.aep.common.EffectKey;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.sideeffect.EffectSpec;
import com.conversive.aep.sideeffect.LedgerEntry;
import com.conversive.aep.sideeffect.LedgerState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code side_effect_ledger} access. Each method is one auto-committed statement (no surrounding transaction, so
 * the external call never runs while a DB transaction is open). Every statement filters {@code tenant_id}; every
 * transition is a compare-and-set whose boolean result says whether this caller won.
 */
@Repository
public class LedgerRepository {

    private static final String COLUMNS = """
            effect_key, tenant_id, execution_id, node_id, phase, call_index, state, idempotency_mode,
            owner_attempt, lease_until, external_ref, response::text AS response
            """;

    private static final String COMMIT = """
            UPDATE side_effect_ledger
            SET state = 'COMMITTED', response = CAST(:response AS jsonb), external_ref = :ref, updated_at = :now
            WHERE tenant_id = :tenant AND effect_key = :key AND state IN ('PENDING', 'UNKNOWN')
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public LedgerRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** {@code INSERT … ON CONFLICT DO NOTHING}; true when this caller created the PENDING row. */
    public boolean insertPending(EffectSpec spec, Instant leaseUntil, Instant now) {
        return jdbc.sql("""
                        INSERT INTO side_effect_ledger (effect_key, tenant_id, execution_id, node_id, phase, call_index,
                            state, idempotency_mode, owner_attempt, lease_until, created_at, updated_at)
                        VALUES (:key, :tenant, :exec, :node, :phase, :callIndex,
                            'PENDING', :mode, :attempt, :lease, :now, :now)
                        ON CONFLICT DO NOTHING
                        """)
                .param("key", spec.key().value())
                .param("tenant", spec.tenantId().value())
                .param("exec", spec.executionId().value())
                .param("node", spec.nodeId())
                .param("phase", spec.phase().name())
                .param("callIndex", spec.callIndex())
                .param("mode", spec.mode().name())
                .param("attempt", spec.attempt())
                .param("lease", ts(leaseUntil))
                .param("now", ts(now))
                .update() == 1;
    }

    public Optional<LedgerEntry> find(TenantId tenantId, EffectKey key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM side_effect_ledger WHERE tenant_id = :tenant AND effect_key = :key")
                .param("tenant", tenantId.value())
                .param("key", key.value())
                .query(this::map)
                .optional();
    }

    /** CAS on the row as last read: moves PENDING(expired)/UNKNOWN to PENDING owned by {@code attempt}. */
    public boolean takeOwnership(LedgerEntry expected, int attempt, Instant leaseUntil, Instant now) {
        return jdbc.sql("""
                        UPDATE side_effect_ledger
                        SET state = 'PENDING', owner_attempt = :attempt, lease_until = :lease, updated_at = :now
                        WHERE tenant_id = :tenant AND effect_key = :key AND state = :state
                          AND owner_attempt = :owner AND lease_until = :expectedLease
                        """)
                .param("attempt", attempt)
                .param("lease", ts(leaseUntil))
                .param("now", ts(now))
                .param("state", expected.state().name())
                .param("owner", expected.ownerAttempt())
                .param("expectedLease", ts(expected.leaseUntil()))
                .param("tenant", expected.tenantId().value())
                .param("key", expected.key().value())
                .update() == 1;
    }

    /** CAS on the row as last read: PENDING(expired) → UNKNOWN (mode NONE, nothing to reconcile with). */
    public boolean markUnknown(LedgerEntry expected, Instant now) {
        return jdbc.sql("""
                        UPDATE side_effect_ledger SET state = 'UNKNOWN', updated_at = :now
                        WHERE tenant_id = :tenant AND effect_key = :key AND state = 'PENDING'
                          AND owner_attempt = :owner AND lease_until = :expectedLease
                        """)
                .param("now", ts(now))
                .param("tenant", expected.tenantId().value())
                .param("key", expected.key().value())
                .param("owner", expected.ownerAttempt())
                .param("expectedLease", ts(expected.leaseUntil()))
                .update() == 1;
    }

    /**
     * 06 §4.9 step 3 for an attempt that made the call: only the row's current owner may commit, so an attempt
     * that outlived its lease cannot commit after another attempt took the row over.
     */
    public boolean commit(TenantId tenantId, EffectKey key, int owner, JsonNode response, String externalRef,
                          Instant now) {
        return jdbc.sql(COMMIT + " AND owner_attempt = :owner")
                .param("owner", owner)
                .param("response", write(response))
                .param("ref", externalRef)
                .param("now", ts(now))
                .param("tenant", tenantId.value())
                .param("key", key.value())
                .update() == 1;
    }

    /** Commit for the reconciler, which found the effect by lookup and owns no attempt: any owner will do. */
    public boolean commit(TenantId tenantId, EffectKey key, JsonNode response, String externalRef, Instant now) {
        return jdbc.sql(COMMIT)
                .param("response", write(response))
                .param("ref", externalRef)
                .param("now", ts(now))
                .param("tenant", tenantId.value())
                .param("key", key.value())
                .update() == 1;
    }

    public boolean markFailed(TenantId tenantId, EffectKey key, int owner, Instant now) {
        return jdbc.sql("""
                        UPDATE side_effect_ledger SET state = 'FAILED', updated_at = :now
                        WHERE tenant_id = :tenant AND effect_key = :key AND state = 'PENDING' AND owner_attempt = :owner
                        """)
                .param("now", ts(now))
                .param("tenant", tenantId.value())
                .param("key", key.value())
                .param("owner", owner)
                .update() == 1;
    }

    /** The owner's call got an answer it could not use: PENDING → UNKNOWN so the effect stays reconcilable. */
    public boolean markUnknownByOwner(TenantId tenantId, EffectKey key, int owner, Instant now) {
        return jdbc.sql("""
                        UPDATE side_effect_ledger SET state = 'UNKNOWN', updated_at = :now
                        WHERE tenant_id = :tenant AND effect_key = :key AND state = 'PENDING' AND owner_attempt = :owner
                        """)
                .param("now", ts(now))
                .param("tenant", tenantId.value())
                .param("key", key.value())
                .param("owner", owner)
                .update() == 1;
    }

    /** The owner's call has ended without an answer about the effect: let the next attempt reconcile at once. */
    public boolean expireLease(TenantId tenantId, EffectKey key, int owner, Instant now) {
        return jdbc.sql("""
                        UPDATE side_effect_ledger SET lease_until = :now, updated_at = :now
                        WHERE tenant_id = :tenant AND effect_key = :key AND state = 'PENDING' AND owner_attempt = :owner
                        """)
                .param("now", ts(now))
                .param("tenant", tenantId.value())
                .param("key", key.value())
                .param("owner", owner)
                .update() == 1;
    }

    /** A 429 on the call of the attempt that created the row: nothing can have landed, so the retry starts clean. */
    public boolean release(TenantId tenantId, EffectKey key, int owner) {
        return jdbc.sql("""
                        DELETE FROM side_effect_ledger
                        WHERE tenant_id = :tenant AND effect_key = :key AND state = 'PENDING' AND owner_attempt = :owner
                        """)
                .param("tenant", tenantId.value())
                .param("key", key.value())
                .param("owner", owner)
                .update() == 1;
    }

    private LedgerEntry map(ResultSet rs, int row) throws SQLException {
        return new LedgerEntry(
                new EffectKey(rs.getString("effect_key")),
                TenantId.of(rs.getString("tenant_id")),
                ExecutionId.of(rs.getString("execution_id")),
                rs.getString("node_id"),
                Phase.valueOf(rs.getString("phase")),
                rs.getInt("call_index"),
                LedgerState.valueOf(rs.getString("state")),
                IdempotencyMode.valueOf(rs.getString("idempotency_mode")),
                rs.getInt("owner_attempt"),
                rs.getObject("lease_until", OffsetDateTime.class).toInstant(),
                rs.getString("external_ref"),
                read(rs.getString("response")));
    }

    /** Postgres keeps microseconds; truncating here keeps CAS comparisons on lease_until exact. */
    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    private JsonNode read(String text) {
        if (text == null) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("side_effect_ledger.response holds invalid JSON", e);
        }
    }

    private String write(JsonNode response) {
        try {
            return mapper.writeValueAsString(response == null ? mapper.nullNode() : response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("provider response is not serialisable", e);
        }
    }
}
