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
| [04 — Local DB analysis](docs/04-local-db-analysis.md) | SQLite vs Postgres for a zero-setup local experience; decision: Postgres app DB + Temporal dev server (`lite`) |
| [05 — Sagas, transactions & concurrency](docs/05-sagas-transactions-and-concurrency.md) | Saga compensation in the DAG interpreter, pivot taxonomy, multi-agent transactions, concurrent-write coordination |
| [06 — Execution plan & module specs](docs/06-execution-plan.md) | Authoritative 16 h schedule, PDF requirements traceability, module specs, E2E flow, migrations, test matrix, adversarial reviews rounds 1–3 |
| [07 — Implementation tasks & DoD](docs/07-implementation-tasks.md) | Task breakdown of 06 per phase, workstreams and interface seams, per-task and per-phase done checklists, release DoD, progress tracker |
