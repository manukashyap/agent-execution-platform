# 03 — Baseline Implementation Plan

> Status: baseline v2 (2026-10-02) — updated after the local-DB analysis ([04](./04-local-db-analysis.md)) and the saga / concurrency analysis ([05](./05-sagas-transactions-and-concurrency.md)). Time box: phase hours below are the **v2 estimates and sum to ~21.5 h, not 17.5 h** (found in review). The authoritative 16.0 h schedule, module specs and review fixes are in [06-execution-plan.md](./06-execution-plan.md); 06 wins on hours, ordering, migration numbering and naming. Stack: Java 21, Spring Boot 3, Temporal Java SDK, Postgres 16, Redis 7 (`full` only), Flyway, Micrometer + OpenTelemetry, Testcontainers, k6.
> Design rationale: [01-requirements-and-design-exploration.md](./01-requirements-and-design-exploration.md) · Engine choice: [02-execution-engine-research.md](./02-execution-engine-research.md)

## Guiding principles

- Correct core engine (§4) + strong design doc beats breadth. Cut from the bottom of the cut-list, never from Phase 2.
- Every Part A feature ships with a test that proves it; every Part B slice ships with a scripted demo.
- Keep the deployable count to one Java app + mocks; roles via Spring profiles.
- **Sagas, not rollbacks:** failures trigger semantic compensation in reverse completion order; guarantee is ACD without isolation. Never claim exactly-once or rollback.
- **Single writer per piece of state:** workflow owns execution state; DB rows mutated only via CAS / conditional updates; no external call inside a DB transaction.
- **One-command local experience without compromising the scale story:** app DB is always Postgres (never SQLite); only Temporal's persistence is lightweight in the `lite` profile.

## Run profiles

| Profile | Services | Use |
|---|---|---|
| `lite` (default) | `postgres` (app DB only), `temporal` (`temporal server start-dev --db-filename /data/temporal.db`, SQLite, UI `:8233`), `mocks`, `app`; no Redis → in-memory limiter | Reviewer quick start, demos, dev. `./gradlew bootRun` auto-starts it via `spring-boot-docker-compose` |
| `full` | `postgres` (app + Temporal DBs), `temporalio/auto-setup`, `temporal-ui` (`:8080`), `redis`, otel-collector, prometheus, grafana, jaeger, `mocks`, `app` | Load test, multi-worker, scale story |
| tests | `TestWorkflowEnvironment` (in-memory Temporal) + Testcontainers Postgres | Unit / integration tests |

## Repository layout (target)

```
agent-execution-platform/
├── compose.yml                   # profiles: lite (postgres, temporal dev server, mocks, app) · full (+ auto-setup, temporal-ui, redis, observability)
├── app/                          # Spring Boot (Gradle)
│   └── src/main/java/.../
│       ├── api/                  # REST controllers, DTOs, admission control
│       ├── definition/           # DAG model, validation, versioning
│       ├── engine/               # DagInterpreterWorkflow, NodeActivity, ExecutorRegistry
│       ├── nodes/                # http, llm, mcp, condition, approval, mock executors
│       ├── router/               # LlmRouter, ProviderHealth, scoring, circuit breaker
│       ├── tools/                # ToolRegistry, ToolGateway, schema validation, audit
│       ├── sideeffect/           # SideEffectGuard, ledger repository
│       ├── tenancy/              # rate limiter, quotas, fair dispatcher
│       ├── cost/                 # budget reservation
│       └── observability/        # metrics, tracing config
├── mocks/                        # single small Spring/WireMock service: llm-a, llm-b, vllm, mcp tools, crm, payments
├── loadtest/                     # k6 scripts + results
├── scripts/                      # demo scripts (failure walk-through, vLLM degradation, dry-run)
└── docs/                         # design doc (→ PDF), architecture diagram, these notes
```

## Phases

### Phase 0 — Skeleton (2–2.5 h)
- [ ] Gradle project, Java 21, Spring Boot 3, Temporal Spring Boot starter
- [ ] `compose.yml` with profiles:
  - `lite`: Postgres (app DB), `temporalio/temporal server start-dev --ip 0.0.0.0 --db-filename /data/temporal.db` (volume-backed so state survives restarts), mocks
  - `full`: Postgres (DBs `app` + `temporal`), `temporalio/auto-setup`, Temporal UI, Redis, observability stack
- [ ] `spring-boot-docker-compose` with `lifecycle-management: start-only`, profile `lite` → `./gradlew bootRun` brings the DB + Temporal up automatically; `spring.docker.compose.enabled=false` when the app runs inside a container
- [ ] Temporal connection configured explicitly (no built-in Spring service-connection for Temporal)
- [ ] Smoke-check `--dynamic-config-value matching.enableFairness=true` on the dev server (unverified)
- [ ] Flyway V1: tenant, tenant_limits, workflow_definition, workflow_execution (**+ `version`**), node_run (**+ `phase` FORWARD/COMPENSATE in PK**), node_output (Postgres only — single migration set)
- [ ] Mock service with latency/failure knobs (`/admin/latency`, `/admin/fail-rate`); payments mock exposes `/charge` **and `/refund`** (both honour `Idempotency-Key`)
- **Exit**: `./gradlew bootRun` from clean checkout → health endpoint green, Flyway applied, Temporal UI on `:8233` (lite) / `:8080` (full)

### Phase 1 — Definitions & API (1.5 h)
- [ ] DAG model + validator (acyclic, deps exist, limits, tool names)
- [ ] Node schema: `compensate` (tool/http + args template + `valid_for_s`), `resource_key`; validator **warns** on compensatable-after-pivot and > 1 pivot per path
- [ ] `POST /workflows` (new immutable version), `GET /workflows/{id}/versions/{v}`
- [ ] `POST /workflows/{id}/executions` (`mode`, `input`, optional `version`, `business_key`) → 202
- [ ] `GET /executions/{id}`, `GET /executions/{id}/nodes`, `DELETE /executions/{id}` (cancel), `POST /executions/{id}/approvals/{nodeId}`
- [ ] API-key → tenant auth filter
- **Exit**: validator unit tests (cycle, missing dep, too many nodes)

### Phase 2 — Core engine (5.5 h) ★ critical path
- [ ] `DagInterpreterWorkflow`: ready-set scheduling, bounded parallelism, `Promise.anyOf` loop
- [ ] `NodeActivity` + `ExecutorRegistry` keyed by `(type, mode)`
- [ ] Executors: `http`, `condition` (in-workflow, sandboxed expr), `approval` (signal + timer), `llm` (stub until Phase 3), `mcp` (stub until Phase 4)
- [ ] Per-node retry/timeout → `ActivityOptions`; non-retryable error taxonomy
- [ ] Failure policy `FAIL_WORKFLOW | CONTINUE` with SKIPPED propagation
- [ ] Cancellation via `CancellationScope` + activity heartbeats
- [ ] Status projection into `workflow_execution` / `node_run`; outputs by reference
- [ ] Version pinning (frozen definition in workflow input)
- [ ] **Saga**: `Saga` (sequential, `continueWithError=true`); `addCompensation` on node completion; `saga.compensate()` inside `Workflow.newDetachedCancellationScope` on `FAIL_WORKFLOW` and on cancel; statuses `COMPENSATING / COMPENSATED / COMPENSATION_FAILED`; compensation logic guarded by `Workflow.getVersion`
- [ ] Status transitions via CAS (`UPDATE … WHERE status = ANY(:allowedFrom)`, `version+1`); terminal states never overwritten
- **Exit tests** (Temporal `TestWorkflowEnvironment` + Testcontainers Postgres):
  sequential · parallel diamond · dependency ordering · retry-then-succeed · timeout · fail-fast vs continue · cancel mid-flight · v3 run unaffected by v4 publish · worker restart resumes ·
  **saga chain 1→5 (node 4 fails → compensate 3,2,1 in order) · saga diamond A→{B,C}→D (B,C before A) · cancel mid-flight still compensates · concurrent status CAS never overwrites terminal**

### Phase 3 — LLM router (2 h)
- [ ] `LlmProvider` interface + 3 mock-backed providers (A, B, vLLM) with cost/latency/capacity config
- [ ] Filter → score (priority-weighted) → pick → fallback chain
- [ ] `ProviderHealth`: sliding-window p95 + error rate, HEALTHY/DEGRADED/OPEN/HALF_OPEN with hysteresis
- [ ] Per-provider token buckets (capacity), tenant model allow-list
- [ ] Persist decision to `llm_call` (candidates + reason)
- **Exit**: unit tests on scoring per priority; scenario test — inject 2.5 s on vLLM → DEGRADED within N windows, traffic shifts to B/A, recovers after latency removed

### Phase 4 — MCP tool layer (1.5 h)
- [ ] `tool_registry` (**+ `reversibility` COMPENSATABLE/PIVOT/READ_ONLY, `compensation` JSONB**), `tenant_tool_grant`, `tool_call_audit` (Flyway V2); registry overrides untrusted MCP annotations (tighten only)
- [ ] `ToolGateway`: discovery (`GET /tools`), JSON-schema validation (networknt), scope check, timeout/retry, audit write
- [ ] Agent mode: LLM node returns tool_call **proposal** → gateway validates (schema, scope, allow-list, budget) → executes + registers compensation → result fed back, bounded `max_tool_iterations`; agent-proposed PIVOT calls rejected unless node allows them (staged plan→validate→commit is design-only)
- **Exit**: tests for invalid args (non-retryable), missing scope (403-style failure), tool timeout retry, audit row per call

### Phase 5 — Side-effect guard, idempotency & compensations (2 h) ★
- [ ] `side_effect_ledger` (Flyway V3) + `SideEffectGuard` (PENDING → call → COMMITTED; NATIVE_KEY / LOOKUP / NONE paths)
- [ ] Mock payments API honours `Idempotency-Key`; mock CRM is non-idempotent with lookup
- [ ] `CompensationActivity` runs through `SideEffectGuard` with key `sha256(tenant:exec:node:compensate)`; retry with backoff bounded by ScheduleToClose (24 h, shortened in tests) + alert
- [ ] Reconcile-before-compensate: forward effect `UNKNOWN`/`PENDING` → LOOKUP → compensate / skip / escalate; `valid_for_s` expired → escalate
- [ ] Pivot already executed → record uncompensated effect in audit, `COMPENSATION_FAILED`
- [ ] Demo `scripts/saga-charge-then-send.sh`: Charge → Send Message, send fails → refund issued exactly once
- [ ] *Stretch (1 h)*: `resource_lease` table + fencing token, renewed on heartbeat
- [ ] `scripts/failure-walkthrough.sh`: Node 4 API takes 15 s, StartToClose 10 s, kill worker at 12 s → show single charge / UNKNOWN escalation
- **Exit**: tests — no double charge on retry after crash between call and commit · crash during compensation → one refund · refund mock always 500 → bounded retries → `COMPENSATION_FAILED`, other compensations still run · UNKNOWN forward effect → no blind compensation · pivot executed → escalation

### Phase 6 — Dry-run (1 h)
- [ ] `MockExecutor` for side-effecting nodes/tools, output from `output_schema` or fixture
- [ ] ~~Record/replay of non-deterministic responses~~ → pre-cut to design-only
- [ ] `GET /executions/{id}/preview` — includes the **compensation plan** (rendered refund args); compensations mocked via `ExecutorRegistry`
- **Exit**: dry-run of lead workflow produces preview with zero calls recorded on mock CRM/messaging "real" endpoints

### Phase 7 — Tenancy & cost slices (1 h)
- [ ] `RateLimiter` interface: `RedisTokenBucketLimiter` (`full`) / `InMemoryTokenBucketLimiter` (`lite`), selected by profile; daily quota counter
- [ ] Temporal fairness: `matching.enableFairness=true`, `Priority(fairnessKey=tenantId, fairnessWeight=tierWeight, priorityKey=tier)` on activity/workflow starts
- [ ] ~~Dispatcher: `execution_queue` + DRR~~ → pre-cut to design-only; keep per-tenant concurrency cap check at admission
- [ ] **Tenant budget as TCC**: `tenant_budget(limit, spent, reserved)`; try = conditional `UPDATE … WHERE spent+reserved+est <= limit RETURNING`, confirm/cancel after the call (READ COMMITTED, no external call in tx); per-execution budget stays in workflow state; max node executions in interpreter
- [ ] Test: 50 concurrent reservations vs a budget for 10 → exactly 10 succeed
- **Exit**: test — tenant A floods, tenant B's executions still start within SLA; runaway agent loop stopped by budget

### Phase 8 — Observability (1 h)
- [ ] Micrometer metrics (the 9 required + `compensations_total{outcome}`, `lock_wait_seconds`) + Prometheus scrape (Grafana dashboard pre-cut → design only)
- [ ] OTel tracing incl. Temporal interceptor → Jaeger
- [ ] `GET /executions/{id}/trace` timeline endpoint
- **Exit**: screenshot of a trace and dashboard in docs

### Phase 9 — Load test (1.5 h) — `full` profile only
- [ ] k6: ramp to N exec/s with a 10-node workflow (2 parallel branches, 1 LLM, 1 MCP), mocks at realistic latency
- [ ] Capture throughput, p50/p95/p99, CPU/mem (`docker stats`), Postgres (`pg_stat_statements`, TPS) for **both** app and Temporal DBs, Temporal schedule-to-start / queue backlog
- [ ] Optional contrast run on `lite` → shows the dev server's single-shard SQLite saturating first (honest bottleneck data point)
- [ ] Identify first bottleneck; write 10× plan
- **Exit**: `loadtest/RESULTS.md`

### Phase 10 — Docs (2 h)
- [ ] Design doc ≤ 5 pages (condense 01, 02, 05) → PDF; include a "Sagas & concurrency" section: ACD-not-ACID, step taxonomy, compensation semantics, agents-propose/platform-executes, resource leases + ETag, scratchpad reducers, entity workflow as production option
- [ ] Architecture diagram (Mermaid/Excalidraw → PNG)
- [ ] README: lead with one-command `lite` quick start; `full` for load test; assumptions, AI-tool usage & overrides, "with more time" (incl. optional SQLite app-DB profile)

## Cut-list (drop in this order if behind)

**Pre-cut (moved to design-only to fund the saga work):** Grafana dashboard (keep raw Prometheus metrics) · fair dispatcher (keep API token bucket + Temporal fairness) · dry-run record/replay (keep mocking).

If still behind, cut next in this order:
1. `resource_lease` stretch → design only
2. Agent tool-calling loop → fixed-tool MCP node only
3. Approval node → design only (`COMPENSATION_FAILED` / needs-attention status is the escalation path)

**Never cut:** Phase 2 (incl. saga), ledger/idempotency + idempotent compensations (Phase 5), router degradation scenario, dry-run mocking, load test numbers.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Temporal determinism bugs in interpreter | Keep all I/O in activities; use `Workflow.currentTimeMillis`, replay tests with `WorkflowReplayer` |
| Local Temporal + Postgres saturate laptop in load test | Report the bottleneck honestly; that's the §15 answer |
| Scope creep in router/tenancy | Time-box each phase; cut-list above |
| Docker-compose too heavy for reviewers | `lite` profile (4 services, auto-started by `bootRun`) vs `full` |
| Dev server ≠ `auto-setup` (single history shard, dynamic config may differ) | Run Phase 2 exit tests once against `full` before Phase 9 |
| Reviewer has no Docker | Optional: zonky embedded Postgres + `brew install temporal` instructions — only if time remains |
| In-memory `TestWorkflowEnvironment` can't show crash/resume or fairness | Use it for unit tests only; crash/resume demos run on `lite`/`full` |
| Reviewers read sagas as "rollback" / isolation | State ACD-not-ACID explicitly in doc; show pivot escalation demo |
| Compensation logic changes break replay | `Workflow.getVersion` around saga code; replay tests |
| Compensations reference outputs by reference | `node_output` retention ≥ execution lifetime + compensation window |
| Unbounded compensation retries pin workflows open | ScheduleToClose cap + alert → `COMPENSATION_FAILED` |
| Lease expires mid external call | Fencing token / ETag `If-Match` on APIs that support it; otherwise lease only reduces conflicts — documented |
| Tenant budget row becomes a hot row at scale | Design doc: sharded sub-counters or Redis |

## Open questions (assumptions to record if unanswered)

- Is a synchronous execute endpoint expected? → assume async 202.
- Are loops in the DAG required? → no; bounded loops only inside agent nodes.
- Should dry-run make real LLM calls? → configurable, default fixtures.
