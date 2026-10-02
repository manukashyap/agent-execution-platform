-- V5 (P7): per-call TCC budget (06 §4.12). Every reservation moves money between three buckets on a
-- counter row: limit >= spent + reserved is enforced by the conditional UPDATE in try, never by a read.

CREATE TABLE tenant_budget (
    tenant_id     text           PRIMARY KEY REFERENCES tenant (id),
    limit_usd     numeric(14, 6) NOT NULL,
    reserved_usd  numeric(14, 6) NOT NULL DEFAULT 0,
    spent_usd     numeric(14, 6) NOT NULL DEFAULT 0,
    period        text           NOT NULL DEFAULT 'MONTHLY',
    period_start  date           NOT NULL DEFAULT date_trunc('month', now())::date,
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT tenant_budget_amounts_chk CHECK (limit_usd >= 0 AND reserved_usd >= 0 AND spent_usd >= 0),
    CONSTRAINT tenant_budget_period_chk CHECK (period IN ('DAILY', 'MONTHLY'))
);

-- Per-execution cap (maxCostUsd), checked in the same transaction as the tenant budget so a runaway
-- forEach stops at the cap even when its iterations reserve concurrently. No FK to workflow_execution:
-- the dev router endpoint reserves for executions that have no row.
CREATE TABLE execution_budget (
    tenant_id     text           NOT NULL REFERENCES tenant (id),
    execution_id  uuid           NOT NULL,
    limit_usd     numeric(14, 6) NOT NULL,
    reserved_usd  numeric(14, 6) NOT NULL DEFAULT 0,
    spent_usd     numeric(14, 6) NOT NULL DEFAULT 0,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, execution_id),
    CONSTRAINT execution_budget_amounts_chk CHECK (limit_usd >= 0 AND reserved_usd >= 0 AND spent_usd >= 0)
);

-- One row per reserved call. ref = caller's call identity (e.g. llm:{node}:{callIndex}:{attempt}:{turn}:{seq}).
CREATE TABLE budget_reservation (
    id            uuid           PRIMARY KEY,
    tenant_id     text           NOT NULL REFERENCES tenant (id),
    execution_id  uuid           NOT NULL,
    node_id       text,
    call_index    integer,
    ref           text           NOT NULL,
    amount_usd    numeric(14, 6) NOT NULL,
    actual_usd    numeric(14, 6),
    status        text           NOT NULL DEFAULT 'RESERVED',
    created_at    timestamptz    NOT NULL DEFAULT now(),
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT budget_reservation_status_chk CHECK (status IN ('RESERVED', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT budget_reservation_amounts_chk CHECK (amount_usd >= 0 AND (actual_usd IS NULL OR actual_usd >= 0))
);
CREATE INDEX budget_reservation_execution_idx ON budget_reservation (tenant_id, execution_id);
-- Open reservations, for a future reaper of reservations whose activity died before confirm/cancel.
CREATE INDEX budget_reservation_open_idx ON budget_reservation (created_at) WHERE status = 'RESERVED';
