# Agent Execution Platform

Production-oriented prototype of an AI workflow & agent execution platform: customer-defined DAGs of HTTP, LLM, MCP/tool, condition, parallel, approval and side-effecting nodes, executed durably at multi-tenant scale.

**Stack (proposed):** Java 21 · Spring Boot 3 · Temporal · Postgres · Redis · OpenTelemetry

## Status

Exploration / planning — no code yet.

## Docs

| Doc | Contents |
|---|---|
| [01 — Requirements & design exploration](docs/01-requirements-and-design-exploration.md) | Functional / non-functional analysis, architecture, execution model, data model, idempotency, router, MCP, dry-run, tenancy, cost, observability |
| [02 — Execution engine research](docs/02-execution-engine-research.md) | Temporal vs Restate, DBOS, Hatchet, Inngest, Conductor, custom Postgres engine, etc. |
| [03 — Baseline plan](docs/03-baseline-plan.md) | Phased implementation plan, exit criteria, cut-list, risks |
