# 03 — Baseline Implementation Plan

> Status: baseline v0 (2026-10-02). Time box: **~16 h** of build + docs. Stack: Java 21, Spring Boot 3, Temporal Java SDK, Postgres 16, Redis 7, Flyway, Micrometer + OpenTelemetry, Testcontainers, k6.
> Design rationale: [01-requirements-and-design-exploration.md](./01-requirements-and-design-exploration.md) · Engine choice: [02-execution-engine-research.md](./02-execution-engine-research.md)

## Guiding principles

- Correct core engine (§4) + strong design doc beats breadth. Cut from the bottom of the cut-list, never from Phase 2.
- Every Part A feature ships with a test that proves it; every Part B slice ships with a scripted demo.
- Keep the deployable count to one Java app + mocks; roles via Spring profiles.

## Repository layout (target)

```
agent-execution-platform/
├── docker-compose.yml            # postgres, temporal, temporal-ui, redis, otel-collector, prometheus, grafana, jaeger, mocks, app
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

### Phase 0 — Skeleton (1.5 h)
- [ ] Gradle project, Java 21, Spring Boot 3, Temporal Spring Boot starter
- [ ] `docker-compose.yml`: Postgres (DBs `app` + `temporal`), `temporalio/auto-setup`, Temporal UI, Redis
- [ ] Flyway V1: tenant, tenant_limits, workflow_definition, workflow_execution, node_run, node_output
- [ ] Mock service with latency/failure knobs (`/admin/latency`, `/admin/fail-rate`)
- [ ] `make up` / single command boots everything
- **Exit**: health endpoint green, Temporal UI reachable, Flyway applied

### Phase 1 — Definitions & API (1.5 h)
- [ ] DAG model + validator (acyclic, deps exist, limits, tool names)
- [ ] `POST /workflows` (new immutable version), `GET /workflows/{id}/versions/{v}`
- [ ] `POST /workflows/{id}/executions` (`mode`, `input`, optional `version`, `business_key`) → 202
- [ ] `GET /executions/{id}`, `GET /executions/{id}/nodes`, `DELETE /executions/{id}` (cancel), `POST /executions/{id}/approvals/{nodeId}`
- [ ] API-key → tenant auth filter
- **Exit**: validator unit tests (cycle, missing dep, too many nodes)

### Phase 2 — Core engine (4 h) ★ critical path
- [ ] `DagInterpreterWorkflow`: ready-set scheduling, bounded parallelism, `Promise.anyOf` loop
- [ ] `NodeActivity` + `ExecutorRegistry` keyed by `(type, mode)`
- [ ] Executors: `http`, `condition` (in-workflow, sandboxed expr), `approval` (signal + timer), `llm` (stub until Phase 3), `mcp` (stub until Phase 4)
- [ ] Per-node retry/timeout → `ActivityOptions`; non-retryable error taxonomy
- [ ] Failure policy `FAIL_WORKFLOW | CONTINUE` with SKIPPED propagation
- [ ] Cancellation via `CancellationScope` + activity heartbeats
- [ ] Status projection into `workflow_execution` / `node_run`; outputs by reference
- [ ] Version pinning (frozen definition in workflow input)
- **Exit tests** (Temporal `TestWorkflowEnvironment` + Testcontainers Postgres):
  sequential · parallel diamond · dependency ordering · retry-then-succeed · timeout · fail-fast vs continue · cancel mid-flight · v3 run unaffected by v4 publish · worker restart resumes

### Phase 3 — LLM router (2 h)
- [ ] `LlmProvider` interface + 3 mock-backed providers (A, B, vLLM) with cost/latency/capacity config
- [ ] Filter → score (priority-weighted) → pick → fallback chain
- [ ] `ProviderHealth`: sliding-window p95 + error rate, HEALTHY/DEGRADED/OPEN/HALF_OPEN with hysteresis
- [ ] Per-provider token buckets (capacity), tenant model allow-list
- [ ] Persist decision to `llm_call` (candidates + reason)
- **Exit**: unit tests on scoring per priority; scenario test — inject 2.5 s on vLLM → DEGRADED within N windows, traffic shifts to B/A, recovers after latency removed

### Phase 4 — MCP tool layer (1.5 h)
- [ ] `tool_registry`, `tenant_tool_grant`, `tool_call_audit` (Flyway V2)
- [ ] `ToolGateway`: discovery (`GET /tools`), JSON-schema validation (networknt), scope check, timeout/retry, audit write
- [ ] Agent mode: LLM node returns tool_call → gateway → result fed back, bounded `max_tool_iterations`
- **Exit**: tests for invalid args (non-retryable), missing scope (403-style failure), tool timeout retry, audit row per call

### Phase 5 — Side-effect guard & idempotency (1.5 h) ★
- [ ] `side_effect_ledger` (Flyway V3) + `SideEffectGuard` (PENDING → call → COMMITTED; NATIVE_KEY / LOOKUP / NONE paths)
- [ ] Mock payments API honours `Idempotency-Key`; mock CRM is non-idempotent with lookup
- [ ] `scripts/failure-walkthrough.sh`: Node 4 API takes 15 s, StartToClose 10 s, kill worker at 12 s → show single charge / UNKNOWN escalation
- **Exit**: test proving no double charge on retry after crash between call and commit

### Phase 6 — Dry-run (1 h)
- [ ] `MockExecutor` for side-effecting nodes/tools, output from `output_schema` or fixture
- [ ] Record/replay of non-deterministic responses keyed by `(node_id, request_hash)`
- [ ] `GET /executions/{id}/preview`
- **Exit**: dry-run of lead workflow produces preview with zero calls recorded on mock CRM/messaging "real" endpoints

### Phase 7 — Tenancy & cost slices (1 h)
- [ ] Redis token bucket per tenant at API; daily quota counter
- [ ] Temporal fairness: `matching.enableFairness=true`, `Priority(fairnessKey=tenantId, fairnessWeight=tierWeight, priorityKey=tier)` on activity/workflow starts
- [ ] Dispatcher: `execution_queue` + DRR across tenants, per-tenant concurrency cap
- [ ] Budget reservation on LLM calls; max node executions in interpreter
- **Exit**: test — tenant A floods, tenant B's executions still start within SLA; runaway agent loop stopped by budget

### Phase 8 — Observability (1 h)
- [ ] Micrometer metrics (the 9 required) + Prometheus scrape + 1 Grafana dashboard JSON
- [ ] OTel tracing incl. Temporal interceptor → Jaeger
- [ ] `GET /executions/{id}/trace` timeline endpoint
- **Exit**: screenshot of a trace and dashboard in docs

### Phase 9 — Load test (1.5 h)
- [ ] k6: ramp to N exec/s with a 10-node workflow (2 parallel branches, 1 LLM, 1 MCP), mocks at realistic latency
- [ ] Capture throughput, p50/p95/p99, CPU/mem (`docker stats`), Postgres (`pg_stat_statements`, TPS), Temporal schedule-to-start / queue backlog
- [ ] Identify first bottleneck; write 10× plan
- **Exit**: `loadtest/RESULTS.md`

### Phase 10 — Docs (2 h)
- [ ] Design doc ≤ 5 pages (condense 01 + 02) → PDF
- [ ] Architecture diagram (Mermaid/Excalidraw → PNG)
- [ ] README: run, assumptions, AI-tool usage & overrides, "with more time"

## Cut-list (drop in this order if behind)

1. Grafana dashboard (keep raw Prometheus metrics)
2. Fair dispatcher → design only (keep API token bucket)
3. Record/replay in dry-run → design only (keep mocking)
4. Agent tool-calling loop → fixed-tool MCP node only
5. Approval node → design only

**Never cut:** Phase 2, ledger/idempotency (Phase 5), router degradation scenario, dry-run mocking, load test numbers.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Temporal determinism bugs in interpreter | Keep all I/O in activities; use `Workflow.currentTimeMillis`, replay tests with `WorkflowReplayer` |
| Local Temporal + Postgres saturate laptop in load test | Report the bottleneck honestly; that's the §15 answer |
| Scope creep in router/tenancy | Time-box each phase; cut-list above |
| Docker-compose too heavy for reviewers | Profile `core` (postgres, temporal, redis, app, mocks) vs `full` (+observability stack) |

## Open questions (assumptions to record if unanswered)

- Is a synchronous execute endpoint expected? → assume async 202.
- Are loops in the DAG required? → no; bounded loops only inside agent nodes.
- Should dry-run make real LLM calls? → configurable, default fixtures.
