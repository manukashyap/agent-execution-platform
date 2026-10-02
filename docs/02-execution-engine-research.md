# 02 — Execution Engine Research: Is Temporal the right choice?

> Status: research snapshot (2026-10-02), produced by a research sub-agent and reviewed. Feature/benchmark claims are cited; re-verify versions before relying on them.

## TL;DR

**Keep Temporal.** It is the only option combining a mature Java SDK, GA per-tenant priority & fairness (May 2026), GA worker versioning, battle-tested crash recovery, signals for human approval, and cancellation. The weak spot is **Postgres as Temporal's own persistence at ~25k activities/s** — it will not hold. Scale story: *Temporal Cloud or Cassandra-backed Temporal in production; Postgres for the prototype.*

Runner-up challengers: **DBOS Transact Java** (Postgres-only, library-style, young SDK) and **Conductor** (native JSON DAGs, weaker per-tenant fairness).

## 1. Comparison

| Engine | Model | Java SDK | Dynamic DAG | Persistence | Tenant fairness / limits | Versioning | Compose-friendly | License | Ops |
|---|---|---|---|---|---|---|---|---|---|
| **Temporal** | Event-sourced history + deterministic replay | Mature, first-class | Yes (interpreter workflow) | Cassandra / MySQL / Postgres / SQLite(dev) | **GA**: priority 1–5, fairness keys + weights, per-key RPS | **GA** worker versioning (Pinned/Auto-upgrade); patching | Yes | MIT | Med-high self-hosted; low on Cloud |
| **Cadence** | Same as Temporal (predecessor) | Mature but older | Yes | Cassandra / MySQL / Postgres | Basic task-list rate limits | Versioning API only | Yes | Apache 2.0 | High |
| **Restate** | Journal + replay, own Raft log | Good (sdk-java 2.x) | Yes (code) | Embedded log/RocksDB — **no Postgres** | Scopes + limit keys, **experimental** (1.7) | Immutable deployments | Very (single binary) | **BSL 1.1** | Low |
| **DBOS Transact Java** | Step checkpoints in *your* Postgres (library) | 1.x (1.0 Jul 2026), Spring starter | Yes (code) | Postgres only | Partitioned queues: per-partition concurrency + rate limit | App version tags | Trivial | MIT | Lowest |
| **Inngest** | Step memoization over HTTP, event-driven | Kotlin 0.2.x; Java "coming soon" | Partial | Postgres/Redis | Excellent (concurrency keys, throttle, priority) | Function versions | Yes | SSPL→Apache delayed | Medium |
| **Hatchet** | Postgres queue + DAG/durable tasks | **None** | Yes | Postgres | CEL concurrency keys, GROUP_ROUND_ROBIN | Basic | Yes | MIT | Medium |
| **Conductor OSS / Orkes** | Server-side JSON DAG interpreter | Mature (Java server) | **Native** (FORK_JOIN_DYNAMIC) | Redis / Postgres / MySQL / Cassandra + ES | Per-task rate/concurrency; weak per-tenant | Versioned defs | Yes (several containers) | Apache 2.0 | Medium |
| **AWS Step Functions** | Managed state machine (ASL) | AWS SDK only | Native (runtime ASL) | Managed | None per tenant | Versions/aliases | No (LocalStack) | Proprietary | Low ops, high $ |
| **Camunda 8 / Zeebe** | BPMN engine, own log | Good | BPMN at runtime | RocksDB/Raft + ES | Weak | Process versions | Heavy | Prod license since 8.6 | High |
| **Airflow / Prefect / Dagster** | Batch/data schedulers | No (Python) | Limited | Postgres metadata | Pools/queues | n/a | Yes | Apache | Poor fit |
| **LangGraph / langgraph4j** | In-process graph + checkpointer | Community | Yes | Postgres saver | None | None | Library | MIT | Library, not an engine |
| **Custom Postgres (SKIP LOCKED)** | DB state machine + queue | Yours | Yes | Postgres | Whatever you build | You build it | Trivial | — | You own every bug |

## 2. Assessments

- **Temporal** — Generic DAG-interpreter workflow is a well-known pattern; signals/updates for approval, cancellation scopes, activity retries/timeouts/heartbeats map 1:1 to node semantics. Priority & Fairness GA (May 2026; self-hosted via `matching.enableFairness=true`): fairness key per tenant, weight per tier (overrides capped at 1,000 keys/queue), per-key RPS limit; accuracy degrades with many keys. Worker Versioning GA (Mar 2026). Costs: determinism discipline, history limits, persistence write load.
- **Cadence** — Same model, lacks the 2026 fairness/versioning features. Only if already invested.
- **Restate** — Elegant, fast, single binary; awakeables fit human-in-the-loop. Against: no Postgres, BSL restricts "Application Platform Service" use, tenant flow control experimental.
- **DBOS Transact Java** — Strongest Postgres-only challenger: in-process library, partitioned queues give per-tenant concurrency/rate limits, MIT. Benchmarks ~43k no-op workflows/s but on a 96-vCPU RDS with WAL flush as bottleneck; 25k steps/s ≈ 50k+ writes/s → huge primary or sharding. Java SDK young (1.0 Jul 2026).
- **Inngest** — Best-in-class flow control but HTTP/event-centric, no production Java SDK, SSPL. Not for a Java backend.
- **Hatchet** — Postgres-native with real fairness primitives; ~5k tasks/s bursts. **No Java SDK** — disqualified.
- **Conductor** — Closest to "user-defined JSON DAGs" (defs are data, dynamic fork/join, HUMAN/WAIT tasks, Java server, Postgres). Weaker on code-level agent loops, tenant fairness, effectively-once. Reasonable #2 for a declarative DSL product.
- **AWS Step Functions** — No compose story; 25k-event history cap, default transition quotas, per-transition pricing at 25k/s is expensive; no tenant fairness.
- **Camunda 8 / Zeebe** — BPMN is the wrong abstraction for LLM DAGs; heavy stack; licensed for production.
- **Airflow / Prefect / Dagster** — Batch schedulers: seconds of scheduler latency, Python-only, no per-request durable execution/signals at 500 starts/s.
- **LangGraph / langgraph4j** — Good model for an agent loop *inside* a node; checkpointing ≠ durable execution (no retries/timeouts/queues/fairness). Use at most inside an activity/child workflow.
- **Custom Postgres engine** — Easy to demo, full fairness control, but in 12–16 h you'd rebuild timers, retries, leases, heartbeats, cancellation, versioning, replay — exactly where reviewers find bugs. Same single-writer Postgres scale ceiling.

## 3. Recommendation

**For the assignment:** Temporal (`temporalio/auto-setup` on Postgres) + separate app Postgres DB.
- One `DagInterpreterWorkflow`; DAG spec pinned at start; HTTP/LLM/tool nodes = activities; conditions/routing/transforms evaluated in workflow code; approval = signal/update + timer; fan-out = `Async.function` promises with an in-workflow semaphore.
- Demonstrate `Priority.newBuilder().setFairnessKey(tenantId).setFairnessWeight(tierWeight)` with `matching.enableFairness`.
- Keep our own admission layer (rate limit, quota, concurrency cap, budget) — fairness is **not** a quota.
- Idempotency key `executionId:nodeId` (attempt-agnostic) + ledger table.
- Dry-run = mock activity executors; replay = `WorkflowReplayer` over stored history.

**For production scale:** Temporal Cloud, or self-hosted Temporal on Cassandra with 4k+ history shards (fixed at cluster creation). 25k activities/s ≈ 75k+ history events/s — a single Postgres primary won't carry it (third-party benchmark: ~4.5k state transitions/s on Postgres at 2,048 shards; Quo migrated Aurora → Cassandra in Jul 2026 for write throughput). Split namespaces/task queues by tier; Nexus for isolation.

**When to switch away from Temporal:**
- Product commits to a declarative, user-editable DSL with server-side interpretation and visual ops → **Conductor**.
- Hard "Postgres-only, no extra cluster" constraint at moderate scale (≤ ~5k steps/s) → **DBOS Java**.
- Temporal fairness accuracy proves insufficient at 10k+ keys → strengthen our admission/dispatcher layer or DBOS partitioned queues.
- Temporal Cloud per-action cost at ~2B actions/day is prohibitive and Cassandra ops aren't acceptable.

## 4. Temporal pitfalls for this use case

| Pitfall | Detail | Mitigation |
|---|---|---|
| History limits | 51,200 events / 50 MB hard (warn at 10,240 / 10 MB). 50-node DAG ≈ 300–600 events — fine | Agent loops as **child workflows** with `continueAsNew` after N iterations |
| Payload size | 2 MB per payload; LLM outputs accumulate. External Storage (claim-check) preview is Python/Go only | Store outputs in Postgres, pass references; custom `PayloadCodec` if needed |
| Determinism | No `UUID.randomUUID()`, wall clock, HashMap iteration order in workflow code | `Workflow.randomUUID/currentTimeMillis`, sorted node order, pure condition evaluator, pinned spec, replay tests in CI |
| Fan-out of 100 | Below the 2,000 pending-activity cap (Cloud figure) but bursty | In-workflow semaphore (`max_parallel`); child workflows beyond ~500 branches |
| Fairness | GA but approximate; weight overrides capped at 1,000/queue | Map **tiers** to weights; per-tenant quotas/concurrency in our own layer; dedicated queues for whales |
| Postgres persistence | Per-shard serialized writes; default `max_connections` saturates; shard count fixed; visibility degrades | pgbouncer, 512–2048 shards, Elasticsearch visibility at scale, **local activities** for cheap nodes |
| Effectively-once | Activities are at-least-once | Idempotency key + dedup ledger on every side-effecting activity |

## Uncertainties

- Self-hosted fairness: no extra server/persistence requirements documented — verify against the deployed server version.
- 2,000 pending-activity cap is a Cloud limit; self-hosted defaults may differ.
- Restate's BSL "Application Platform Service" wording is ambiguous.
- Temporal-on-Postgres ~4.5k transitions/s is a third-party benchmark.

## Sources

- Temporal Priority & Fairness — https://docs.temporal.io/develop/task-queue-priority-fairness
- Temporal Durable Digest May 2026 — https://temporal.io/blog/durable-digest-may-2026
- Temporal fairness pattern — https://docs.temporal.io/design-patterns/fairness
- Worker Versioning GA — https://temporal.io/blog/ga-worker-versioning-public-preview-upgrade-on-continue-as-new
- Nexus GA — https://temporal.io/blog/temporal-nexus-now-available
- Temporal limits — https://docs.temporal.io/cloud/limits
- Blob size limit — https://docs.temporal.io/troubleshooting/blob-size-limit-error
- External Storage preview — https://temporal.io/changelog/external-storage-public-preview
- Scaling Temporal — https://temporal.io/blog/scaling-temporal-the-basics
- Temporal + Postgres benchmark (3rd party) — https://markaicode.com/stack/temporal-kubernetes-stack/
- Quo Postgres → Cassandra — https://www.quo.com/blog/postgres-to-cassandra/
- PlanetScale Temporal sharding — https://planetscale.com/blog/temporal-workflows-at-scale-sharding-in-production
- Restate flow control — https://docs.restate.dev/services/flow-control
- Restate v1.7.0 notes — https://github.com/restatedev/restate/blob/main/release-notes/v1.7.0.md
- Restate license discussion — https://news.ycombinator.com/item?id=42821705
- DBOS Java queues — https://docs.dbos.dev/java/tutorials/queue-tutorial
- DBOS Postgres benchmark — https://www.dbos.dev/blog/benchmarking-workflow-execution-scalability-on-postgres
- DBOS Transact Java releases — https://github.com/dbos-inc/dbos-transact-java/releases
- Inngest concurrency — https://www.inngest.com/docs/guides/concurrency
- inngest-kt — https://github.com/inngest/inngest-kt
- Hatchet concurrency — https://docs.hatchet.run/v1/concurrency
- Hatchet docs — https://docs.hatchet.run/v1
- Hatchet v1 HN — https://news.ycombinator.com/item?id=43572733
- Conductor dynamic fork — https://conductor-oss.github.io/conductor/documentation/configuration/workflowdef/operators/dynamic-fork-task.html
- Conductor FAQ — https://conductor-oss.github.io/conductor/devguide/faq.html
- Conductor issue #1257 — https://github.com/conductor-oss/conductor/issues/1257
- Step Functions quotas — https://docs.aws.amazon.com/step-functions/latest/dg/service-quotas.html
- Step Functions Map state — https://docs.aws.amazon.com/step-functions/latest/dg/state-map.html
- Camunda licensing — https://camunda.com/blog/2024/04/licensing-update-camunda-8-self-managed/
- Cadence — https://github.com/cadence-workflow/cadence
- langgraph4j Postgres checkpoint — https://langgraph4j.github.io/langgraph4j/core/checkpoint-postgres/
- Checkpoints ≠ durable execution — https://www.diagrid.io/blog/checkpoints-are-not-durable-execution-why-langgraph-crewai-google-adk-and-others-fall-short-for-production-agent-workflows
