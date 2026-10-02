-- V3 (P3): one row per LLM provider call made by the router (06 §4.7). Append-only audit; no FK to
-- workflow_execution so router calls outside an execution (dev debug endpoint) can be recorded too.

CREATE TABLE llm_call (
    id                 bigserial PRIMARY KEY,
    tenant_id          text           NOT NULL,
    execution_id       uuid           NOT NULL,
    node_id            text           NOT NULL,
    call_index         integer        NOT NULL DEFAULT 0,
    attempt            integer        NOT NULL DEFAULT 1,
    turn               integer        NOT NULL DEFAULT 0,
    seq                integer        NOT NULL DEFAULT 0,
    provider           text           NOT NULL,
    model              text           NOT NULL,
    priority           text           NOT NULL,
    candidates         jsonb          NOT NULL,
    reason             text           NOT NULL,
    prompt_tokens      integer        NOT NULL DEFAULT 0,
    completion_tokens  integer        NOT NULL DEFAULT 0,
    cost_usd           numeric(12, 6) NOT NULL DEFAULT 0,
    latency_ms         bigint         NOT NULL,
    outcome            text           NOT NULL,
    error_code         text,
    created_at         timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT llm_call_priority_chk CHECK (priority IN ('HIGH', 'NORMAL', 'LOW')),
    CONSTRAINT llm_call_outcome_chk CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT llm_call_counts_chk CHECK (
        call_index >= 0 AND attempt >= 1 AND turn >= 0 AND seq >= 0
        AND prompt_tokens >= 0 AND completion_tokens >= 0 AND cost_usd >= 0 AND latency_ms >= 0)
);
CREATE INDEX llm_call_tenant_exec_idx ON llm_call (tenant_id, execution_id);
