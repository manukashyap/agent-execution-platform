-- V4 (P4): per-tenant tool grants and the tool-call audit (06 §4.8).

CREATE TABLE tenant_tool_grant (
    tenant_id  text   NOT NULL REFERENCES tenant (id),
    tool_name  text   NOT NULL REFERENCES tool_registry (tool_name),
    scopes     text[] NOT NULL DEFAULT '{}',
    PRIMARY KEY (tenant_id, tool_name)
);

-- One row per gateway attempt. Arguments are stored only as a SHA-256 of their canonical JSON; no FK to
-- workflow_execution so the audit outlives retention of executions (append-only, like llm_call).
CREATE TABLE tool_call_audit (
    id            bigserial PRIMARY KEY,
    tenant_id     text        NOT NULL,
    execution_id  uuid        NOT NULL,
    node_id       text        NOT NULL,
    call_index    integer     NOT NULL,
    phase         text        NOT NULL,
    attempt       integer     NOT NULL,
    tool_name     text        NOT NULL,
    args_sha256   text        NOT NULL,
    outcome       text        NOT NULL,
    error_code    text,
    latency_ms    bigint      NOT NULL,
    effect_key    text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tool_call_audit_phase_chk CHECK (phase IN ('FORWARD', 'COMPENSATE')),
    CONSTRAINT tool_call_audit_outcome_chk CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'IN_PROGRESS')),
    CONSTRAINT tool_call_audit_hash_chk CHECK (args_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT tool_call_audit_numbers_chk CHECK (call_index >= 0 AND attempt >= 1 AND latency_ms >= 0)
);
CREATE INDEX tool_call_audit_tenant_exec_idx ON tool_call_audit (tenant_id, execution_id);

-- t_dev may use every tool with the scopes it requires; t_other only the two read tools, so the
-- forbidden path is testable.
INSERT INTO tenant_tool_grant (tenant_id, tool_name, scopes)
SELECT 't_dev', tool_name, scopes FROM tool_registry;

INSERT INTO tenant_tool_grant (tenant_id, tool_name, scopes)
SELECT 't_other', tool_name, scopes FROM tool_registry WHERE tool_name IN ('leads.fetch', 'crm.get');
