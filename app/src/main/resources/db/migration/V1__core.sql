-- V1 (P0): tenancy, definitions, execution projection. Vocabulary: docs/06-execution-plan.md §0.

CREATE TABLE tenant (
    id          text PRIMARY KEY,
    name        text        NOT NULL,
    tier        text        NOT NULL DEFAULT 'STANDARD',
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenant_tier_chk CHECK (tier IN ('FREE', 'STANDARD', 'ENTERPRISE'))
);

CREATE TABLE tenant_limits (
    tenant_id            text PRIMARY KEY REFERENCES tenant (id),
    rate_per_sec         numeric(10, 2) NOT NULL DEFAULT 10,
    burst                integer        NOT NULL DEFAULT 20,
    max_concurrent       integer        NOT NULL DEFAULT 50,
    max_cost_usd         numeric(12, 4) NOT NULL DEFAULT 5,
    max_tokens           bigint         NOT NULL DEFAULT 200000,
    max_node_executions  integer        NOT NULL DEFAULT 500,
    max_fanout           integer        NOT NULL DEFAULT 100,
    max_tool_calls       integer        NOT NULL DEFAULT 3,
    max_duration_s       integer        NOT NULL DEFAULT 3600,
    CONSTRAINT tenant_limits_positive_chk CHECK (
        rate_per_sec > 0 AND burst > 0 AND max_concurrent > 0 AND max_cost_usd >= 0 AND max_tokens >= 0
        AND max_node_executions > 0 AND max_fanout > 0 AND max_tool_calls >= 0 AND max_duration_s > 0)
);

CREATE TABLE api_key (
    key_hash    text PRIMARY KEY,
    tenant_id   text        NOT NULL REFERENCES tenant (id),
    scopes      text[]      NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz,
    CONSTRAINT api_key_hash_chk CHECK (key_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX api_key_tenant_idx ON api_key (tenant_id);

CREATE TABLE workflow_definition (
    tenant_id    text        NOT NULL REFERENCES tenant (id),
    workflow_id  text        NOT NULL,
    def_version  integer     NOT NULL,
    spec         jsonb       NOT NULL,
    sha256       text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, workflow_id, def_version),
    CONSTRAINT workflow_definition_version_chk CHECK (def_version > 0)
);

CREATE TABLE workflow_execution (
    id                   uuid PRIMARY KEY,
    tenant_id            text        NOT NULL REFERENCES tenant (id),
    workflow_id          text        NOT NULL,
    def_version          integer     NOT NULL,
    mode                 text        NOT NULL DEFAULT 'LIVE',
    status               text        NOT NULL DEFAULT 'QUEUED',
    priority             text        NOT NULL DEFAULT 'NORMAL',
    row_version          bigint      NOT NULL DEFAULT 0,
    idempotency_key      text,
    source_execution_id  uuid REFERENCES workflow_execution (id),
    input                jsonb,
    options              jsonb,
    output               jsonb,
    error_code           text,
    error_message        text,
    started_at           timestamptz,
    deadline_at          timestamptz,
    ended_at             timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workflow_execution_idem_uq UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT workflow_execution_definition_fk FOREIGN KEY (tenant_id, workflow_id, def_version)
        REFERENCES workflow_definition (tenant_id, workflow_id, def_version),
    CONSTRAINT workflow_execution_mode_chk CHECK (mode IN ('LIVE', 'DRY_RUN', 'REPLAY')),
    CONSTRAINT workflow_execution_priority_chk CHECK (priority IN ('HIGH', 'NORMAL', 'LOW')),
    CONSTRAINT workflow_execution_status_chk CHECK (status IN (
        'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT',
        'COMPENSATING', 'COMPENSATED', 'COMPENSATION_FAILED', 'NEEDS_ATTENTION', 'START_FAILED'))
);
-- Soft concurrency cap (06 §4.5): live rows per tenant, bounded by deadline_at so leaked slots expire.
CREATE INDEX workflow_execution_live_idx ON workflow_execution (tenant_id, deadline_at)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX workflow_execution_tenant_created_idx ON workflow_execution (tenant_id, created_at DESC);

CREATE TABLE node_run (
    tenant_id      text        NOT NULL,
    execution_id   uuid        NOT NULL REFERENCES workflow_execution (id),
    node_id        text        NOT NULL,
    call_index     integer     NOT NULL DEFAULT 0,
    phase          text        NOT NULL DEFAULT 'FORWARD',
    attempt        integer     NOT NULL DEFAULT 1,
    status         text        NOT NULL DEFAULT 'PENDING',
    error_code     text,
    error_message  text,
    started_at     timestamptz,
    ended_at       timestamptz,
    PRIMARY KEY (execution_id, node_id, call_index, phase, attempt),
    CONSTRAINT node_run_phase_chk CHECK (phase IN ('FORWARD', 'COMPENSATE')),
    CONSTRAINT node_run_status_chk CHECK (status IN (
        'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED', 'NEEDS_ATTENTION')),
    CONSTRAINT node_run_call_index_chk CHECK (call_index >= 0 AND attempt >= 1)
);
CREATE INDEX node_run_tenant_exec_idx ON node_run (tenant_id, execution_id);

CREATE TABLE node_output (
    tenant_id     text        NOT NULL,
    execution_id  uuid        NOT NULL REFERENCES workflow_execution (id),
    node_id       text        NOT NULL,
    call_index    integer     NOT NULL DEFAULT 0,
    attempt       integer     NOT NULL,
    payload       jsonb       NOT NULL,
    sha256        text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (execution_id, node_id, call_index)
);
CREATE INDEX node_output_tenant_exec_idx ON node_output (tenant_id, execution_id);

-- Tenants only; API keys are created from env-provided plaintext by the dev profile (DevApiKeySeeder).
INSERT INTO tenant (id, name, tier) VALUES
    ('t_dev', 'Dev tenant', 'STANDARD'),
    ('t_other', 'Second tenant (cross-tenant tests)', 'FREE');
INSERT INTO tenant_limits (tenant_id) VALUES ('t_dev'), ('t_other');
