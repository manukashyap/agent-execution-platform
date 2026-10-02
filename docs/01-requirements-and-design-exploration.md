# 01 — Requirements Analysis & Design Exploration

> Status: initial exploration (2026-10-02). Source: *Conversive AI — Technical Assignment: Production-Grade AI Workflow & Agent Execution Platform*.
> Stack under evaluation: **single Java backend (Java 21 + Spring Boot 3) · Postgres · Temporal**. Engine alternatives are evaluated in [02-execution-engine-research.md](./02-execution-engine-research.md).

---

## 1. Objective

Build a production-oriented prototype of a platform that lets a customer define workflows as a **DAG of nodes** (HTTP, LLM, MCP/tool, custom operators, conditions, parallel branches, human approval, side-effecting actions) and execute them **reliably at scale**.

Evaluators explicitly weight **reasoning about failure, distributed execution, AI-provider behaviour, multi-tenancy, observability, cost and replay** over happy-path completeness.

Constraints that shape every decision:

- Time box **12–16 h**, submit in 2 days → prioritise a correct core engine (§4) + strong design doc.
- Must start with **docker-compose / one command**.
- LLM + MCP providers **may be mocked**.
- Design doc ≤ **5 pages**; README must cover run steps, assumptions, AI-tool usage, "with more time".

---

## 2. Functional requirements

### Part A — must be built and running

| # | Requirement | Acceptance signal |
|---|---|---|
| §4 | Execution engine: sequential, parallel, node dependencies, retries, timeouts, failure handling, cancellation, persistent state, basic versioning | Integration tests per capability; kill-worker demo resumes |
| §5 | LLM router over A / B / self-hosted vLLM considering availability, rate limits, cost, latency, capability, tenant config, priority | Unit tests on scoring; **scenario test: vLLM p95 > 2 s → traffic shifts, recovers after probe** |
| §6 | MCP-style tool layer: registration/discovery, input validation, timeout/retry, authN/Z boundary, failures, side effects, audit | Tool registry + JSON-schema validation + scope check + `tool_call_audit` rows |
| §7 | Dry-run / preview: no production side effects, preview outputs, deterministic replay, max code reuse | Same workflow run in `DRY_RUN` mode touches zero side-effect mocks' "real" endpoints |
| §13 | REST API (create / execute / inspect), persistence, worker model, tests, load test | `docker compose up`, curl script, k6/Gatling report |

### Part B — design doc (implement slices where it sharpens the argument)

| # | Topic | Slice worth implementing |
|---|---|---|
| §8 | Execution semantics & idempotency | **Yes** — side-effect ledger + idempotency keys (core to correctness) |
| §9 | Failure walk-through (10 s timeout vs 15 s API, crash, network loss, resume, re-run, new version, rate-limit) | **Yes** — scripted demo using a slow mock API |
| §10 | Multi-tenancy & isolation | Partial — per-tenant token bucket + concurrency cap + fair dispatcher |
| §11 | Cost & safety controls | Partial — per-execution budget reservation + node-execution cap |
| §12 | Observability | Partial — OTel traces + the 9 named metrics |

---

## 3. Non-functional requirements — analysis

| NFR | Target | Derived implication |
|---|---|---|
| Tenants | 10,000 | Logical isolation on shared infra; per-tenant queues/namespaces don't scale to 10k → tenant is a *key*, not a resource |
| Volume | 1 M exec/day (~12/s avg) | Average is trivial; **peak** dominates sizing |
| Peak | 500 exec/s | × 5–50 nodes ⇒ **2.5k–25k node executions/s**; each node = several persisted state transitions ⇒ **~50k–150k DB writes/s** worst case in the orchestration store |
| Workflow size | 5–50 nodes | Small per-execution history; no need for continue-as-new in the interpreter |
| Fan-out | ≤ 100 parallel ops | Bounded parallelism per execution; payloads passed by reference to keep history small |
| Availability | 99.95 % (~22 min/month) | HA orchestrator + HA Postgres; API must accept work even if workers are degraded (durable admission) |
| Non-AI node success | 99.9 % | Retries w/ backoff + jitter, circuit breakers, rate-limit-aware retry, DLQ/human escalation |
| AI node success | unspecified | Assume best-effort with multi-provider failover; state the assumption |
| Latency | unspecified | Assume async API (202 + poll/webhook); report p50/p95/p99 of end-to-end execution |
| Security | implicit | Tenant isolation on every query, tool scopes, secrets never in workflow history, audit trail |
| Cost | implicit | Hard budgets enforced *before* spend, not after |

**Key NFR insight:** the bottleneck at peak is **persistence of orchestration state**, not CPU. Temporal on Postgres will hit write limits well before 25k activities/s — production would need Cassandra-backed Temporal or Temporal Cloud with enough history shards. This is the honest answer for §15 ("where does the bottleneck appear first").

---

## 4. Assumptions (to be restated in README)

1. Workflow execution is **asynchronous**: `POST /executions` → `202 {execution_id}`; clients poll or receive a webhook.
2. Definitions are **immutable per version**; a running execution is pinned to the version it started with.
3. Node outputs > 64 KB are stored in Postgres (`node_output` table) and passed between nodes **by reference**.
4. Loops are not part of the DAG model, but an LLM/agent node may iterate internally (tool-calling loop) → that is where unbounded-loop cost risk lives (§11).
5. Human approval = external signal with timeout; default on timeout = fail the branch.
6. Auth is a simple API key → tenant mapping (no OAuth in prototype).
7. All external providers (LLM A/B/vLLM, MCP tools, CRM, payments) are mock services with injectable latency/failure.

---

## 5. High-level architecture

```
                ┌──────────────────────────── Spring Boot app (one deployable, multiple roles) ─────────────────────────────┐
 Client ──────► │ REST API ── AdmissionControl (tenant token bucket, quota, budget pre-check)                                │
                │   │                                                                                                       │
                │   ├─► Postgres (app schema): definitions, executions, node_runs, ledger, audit, routing decisions         │
                │   │                                                                                                       │
                │   └─► Dispatcher (fair across tenants, DRR) ──► Temporal: start DagInterpreterWorkflow(executionId, dag)  │
                │                                                                                                           │
                │ Worker role:                                                                                              │
                │   DagInterpreterWorkflow  ── schedules ready nodes as activities, bounded parallelism                     │
                │   Activities: HttpNode │ LlmNode ─► LlmRouter │ McpNode ─► ToolGateway │ Condition (in-workflow)          │
                │               Approval (signal + timer) │ Custom                                                          │
                │   SideEffectGuard (ledger + idempotency keys) wraps every side-effecting activity                        │
                └───────────────────────────────────────────────────────────────────────────────────────────────────────────┘
                         │                       │                                │
                 Temporal server          Redis (rate-limit buckets,       OTel collector → Prometheus/Grafana
                 (+ its own Postgres DB)   router health window)             + Jaeger/Tempo
                         │
                 Mock services: llm-a, llm-b, vllm (latency knob), mcp-tools, crm, payments
```

- **One Java deployable**, roles toggled by profile (`api`, `worker`, `dispatcher`) so it can scale horizontally per role.
- **App DB is always Postgres.** Temporal persistence is profile-dependent: `lite` uses the Temporal dev server (embedded SQLite, zero setup); `full` uses a second logical DB `temporal` on the same Postgres instance. Production separates them (Cassandra/Temporal Cloud at scale). SQLite for the app DB was evaluated and rejected — see [04-local-db-analysis.md](./04-local-db-analysis.md).
- **Redis** is optional for the prototype (in-memory fallback) but required for a correct multi-instance rate-limit/health story.

---

## 6. Workflow execution model

### 6.1 Definition schema (extends the assignment's example)

```json
{
  "workflow_id": "lead-enrichment",
  "version": 3,
  "limits": { "max_cost_usd": 1.00, "max_tokens": 50000, "max_duration_s": 600, "max_parallel": 20 },
  "nodes": [
    {"id": "fetch_contact", "type": "http", "config": {"url": "...", "method": "GET"}, "timeout_s": 5,
     "retry": {"max_attempts": 3, "backoff": "exponential"}},
    {"id": "classify", "type": "llm", "depends_on": ["fetch_contact"],
     "config": {"capability": "classification", "prompt_template": "...", "priority": "normal"}},
    {"id": "enrich", "type": "mcp", "depends_on": ["classify"],
     "config": {"tool": "crm.lookup_company", "args": {"domain": "{{fetch_contact.output.domain}}"}}},
    {"id": "decision", "type": "condition", "depends_on": ["enrich"],
     "config": {"expr": "classify.output.label == 'hot'", "true": ["notify"], "false": []}},
    {"id": "notify", "type": "mcp", "depends_on": ["decision"], "side_effecting": true,
     "config": {"tool": "messaging.send"}}
  ]
}
```

Validated on save: acyclic (Kahn's algorithm), all `depends_on` exist, node count ≤ 50, static fan-out ≤ limit, templates reference upstream nodes only, tool names exist in the registry.

### 6.2 DagInterpreterWorkflow (generic)

One Temporal workflow type runs **every** customer DAG. Input = `{executionId, tenantId, mode, frozenDefinition}` — the definition is frozen into workflow input, so later edits never affect in-flight runs and replay is deterministic.

```
ready  = nodes with no deps
while (pending nodes exist and not cancelled):
    launch ready nodes up to max_parallel → Async.function(activityStub::run, nodeInput)
    Promise.anyOf(inFlight).get()                     // wake on first completion
    for each completed node:
        record result ref; charge budget counters (deterministic, in workflow state)
        if failed and node.on_failure == FAIL_WORKFLOW → cancel scope, run compensations, fail
        if condition node → mark non-taken branches SKIPPED (propagate skip to descendants)
        add newly-unblocked children to ready
    enforce: node_exec_count ≤ max, elapsed ≤ max_duration, cost ≤ budget
```

- **Condition nodes** evaluate inside the workflow (pure, deterministic) — no activity round-trip.
- **Approval nodes**: `Workflow.await(timeout, () -> approvals.containsKey(nodeId))` driven by an `approve(nodeId, decision)` signal.
- **Cancellation**: `DELETE /executions/{id}` → Temporal cancel → `CancellationScope` cancels in-flight activities (activities heartbeat to observe it) → compensations for committed side effects if defined.
- **Retries & timeouts**: per-node `ActivityOptions` (StartToClose, ScheduleToClose, RetryOptions with non-retryable error types such as `ValidationError`, `BudgetExceeded`, `SideEffectUnknown`).
- **Versioning**:
  - *Definition* versioning — immutable `(workflow_id, version)` rows; execution pins version.
  - *Engine code* versioning — `Workflow.getVersion()` / worker build-id versioning for interpreter changes.

### 6.3 Node executor contract (shared by prod and dry-run)

```java
public interface NodeExecutor {
    NodeType type();
    boolean sideEffecting(NodeSpec spec);        // static default per type, overridable per node/tool
    NodeResult execute(NodeContext ctx, NodeSpec spec, Map<String, Object> inputs);
}
```

`NodeActivityImpl` resolves the executor from an `ExecutorRegistry` keyed by `(type, mode)`. In `DRY_RUN`, side-effecting executors are swapped for `MockExecutor`s; everything else (templating, validation, routing, tracing, budget accounting) is the same code path.

---

## 7. Data model (app schema, Postgres)

```sql
tenant(id, name, tier, api_key_hash, created_at)
tenant_limits(tenant_id PK, rps, max_concurrent_executions, daily_execution_quota,
              monthly_budget_usd, priority_weight)

workflow_definition(tenant_id, workflow_id, version, dag JSONB, checksum, created_at,
                    PRIMARY KEY (tenant_id, workflow_id, version))          -- immutable

workflow_execution(id UUID PK, tenant_id, workflow_id, version, mode {PROD,DRY_RUN},
                   status {QUEUED,RUNNING,SUCCEEDED,FAILED,CANCELLED,TIMED_OUT},
                   input_ref, budget_usd, cost_spent_usd, tokens_used,
                   temporal_workflow_id, temporal_run_id, parent_execution_id,  -- "Run Again" lineage
                   created_at, started_at, finished_at, error)
  INDEX (tenant_id, status, created_at)

node_run(execution_id, node_id, attempt, status, started_at, finished_at,
         output_ref, error_type, error_msg, cost_usd, tokens_in, tokens_out,
         provider, model, PRIMARY KEY (execution_id, node_id, attempt))

node_output(id UUID PK, execution_id, node_id, payload JSONB, size_bytes)

side_effect_ledger(idempotency_key PK, tenant_id, execution_id, node_id,
                   state {PENDING,COMMITTED,FAILED,UNKNOWN}, request_hash,
                   external_ref, response JSONB, created_at, updated_at)

tool_registry(tool_name PK, version, input_schema JSONB, output_schema JSONB,
              side_effecting BOOL, required_scopes TEXT[], timeout_ms, retry_policy JSONB,
              idempotency_support {NATIVE_KEY,LOOKUP,NONE})
tenant_tool_grant(tenant_id, tool_name, scopes TEXT[])
tool_call_audit(id, tenant_id, execution_id, node_id, tool_name, args_hash, args_redacted JSONB,
                outcome, latency_ms, idempotency_key, mode, created_at)

llm_call(id, execution_id, node_id, provider, model, priority, candidates JSONB,
         decision_reason TEXT, latency_ms, tokens_in, tokens_out, cost_usd, outcome, created_at)

execution_queue(tenant_id, execution_id, priority, enqueued_at)   -- dispatcher backlog
```

Partition `node_run`, `tool_call_audit`, `llm_call` by month (time-range) in production; prototype keeps them unpartitioned.

---

## 8. State management

- **Temporal history** is the source of truth for *orchestration progress* (which nodes completed, timers, signals).
- **App DB** is the source of truth for *business/queryable state* (status, costs, outputs, audit) — projected by activities and a small "status projector" activity at node/workflow boundaries.
- Keep Temporal payloads small: node outputs go to `node_output`, workflow carries `output_ref` UUIDs. Avoids the 2 MB per-payload / 50k-event history limits.
- Secrets (tool credentials, API keys) are resolved **inside activities** from a secret store; never placed in workflow input/history.

---

## 9. Failure, retry & idempotency semantics (§8, §9)

### 9.1 Guarantee

**Exactly-once across external systems is not achievable** (a crash between "external system committed" and "we recorded it" is indistinguishable from "request never arrived"). We provide:

> **At-least-once activity execution + effectively-once side effects** via stable idempotency keys and a side-effect ledger, with an explicit `UNKNOWN` state that is **never blindly retried**.

### 9.2 Idempotency keys

`key = sha256(tenant_id : execution_id : node_id [: fan_out_index])`

- Stable across retries and worker crashes (does **not** include attempt number).
- "Run Again" creates a new `execution_id` → new key → intentional new side effect. Optional user-supplied `business_key` lets a tenant dedupe re-runs (e.g. "charge order #123 once").

### 9.3 SideEffectGuard protocol

```
1. INSERT ledger(key, PENDING, request_hash) ON CONFLICT DO NOTHING       -- tx boundary #1
2. if existing row:
     COMMITTED → return stored response (duplicate detected, no call)
     PENDING/UNKNOWN →
        tool.idempotency_support == NATIVE_KEY → re-send with same key (provider dedupes)
        == LOOKUP → query provider by key/external_ref → reconcile to COMMITTED/FAILED
        == NONE   → mark UNKNOWN, throw non-retryable SideEffectUnknown → human review
3. call external API with Idempotency-Key header
4. UPDATE ledger SET COMMITTED, external_ref, response                    -- tx boundary #2
```

Transaction boundaries: (a) ledger PENDING write, (b) external call (outside any DB tx), (c) ledger COMMITTED write, (d) Temporal activity completion. A crash between (b) and (c) is the §8 scenario — handled by step 2.

### 9.4 §9 walk-through (Node 4 calls external API; worker timeout 10 s; API succeeds at 15 s)

| Variation | What happens | Protection |
|---|---|---|
| Base case | StartToClose 10 s fires at 10 s; Temporal retries Node 4 while first call still lands at 15 s | Set StartToClose > API p99 (e.g. 30 s) + heartbeat; on retry the ledger shows PENDING → native key dedupe / lookup reconcile |
| API not idempotent | Retry could double-execute | `idempotency_support=NONE` → PENDING on retry becomes `UNKNOWN`, workflow parks Node 4 for human/reconciliation; Node 5 waits; Nodes 3 & 6 unaffected |
| Worker crashes at 12 s | No heartbeat → heartbeat timeout → activity rescheduled on another worker | Same ledger path; workflow state itself is safe in Temporal history |
| Network lost | Activity can't report completion; times out | Retry hits ledger; response re-read from provider (LOOKUP) or deduped (NATIVE_KEY) |
| Resume 30 min later | Temporal replays history; completed nodes not re-run | ScheduleToClose bounds total wait; budget/time limits still apply; stale-data check optional |
| Customer clicks "Run Again" | New execution, new keys | Explicit new side effects; optional `business_key` dedupe; UI warns if a prior run is UNKNOWN |
| New workflow version deployed | In-flight run keeps frozen v3 definition | Definition pinned in input; interpreter code changes guarded by `getVersion`/build-id |
| API rate-limited (429) | Retry with `Retry-After` honoured; per-API token bucket before calling | 429 classified as retryable-with-delay, does not consume the failure retry budget beyond a cap; circuit breaker opens on sustained 429/5xx |

Parallel branches (3, 4→5, 6) are independent promises: one branch's failure fails the workflow only if `on_failure=FAIL_WORKFLOW`; otherwise `CONTINUE` marks descendants `SKIPPED`.

### 9.5 Sagas & compensation

On a `FAIL_WORKFLOW` failure or cancellation, completed compensatable nodes are compensated in reverse completion order (= reverse topological) via Temporal `Saga` in a detached cancellation scope; compensations are idempotent through the ledger, reconcile `UNKNOWN` effects first, and pivots (sent messages, LLM spend) escalate instead. Guarantee is **ACD, not ACID** — semantic undo, no isolation. Concurrent-write coordination (status CAS, tenant budget TCC, resource leases + fencing, scratchpad reducers) is in [05-sagas-transactions-and-concurrency.md](./05-sagas-transactions-and-concurrency.md).

---

## 10. AI / LLM Provider Router (§5)

### 10.1 Selection pipeline

1. **Filter** — capability match (`classification`, `tool_use`, context length), tenant allow/deny list & data-residency, circuit state ≠ OPEN, token bucket has headroom.
2. **Score** — `score = w_cost·norm(cost) + w_lat·norm(p95_latency) + w_load·utilisation`, weights chosen by **priority**:
   - `HIGH` → latency-heavy; `NORMAL` → balanced; `LOW/BATCH` → cost-heavy (may queue).
3. **Pick** — lowest score; weighted-random among near-ties to avoid herding.
4. **Fallback chain** — on retryable failure, next candidate (excluding the failed one), bounded by node retry budget.
5. **Record** — every decision persisted to `llm_call.candidates/decision_reason` (answers "why did the router choose it?").

### 10.2 Health tracking & the vLLM degradation scenario

- Per provider: sliding window (e.g. 30 s, HdrHistogram) of latency + error rate; shared via Redis for multi-instance.
- State machine: `HEALTHY → DEGRADED (p95 > 2 s for 3 consecutive windows) → OPEN (error rate > 50% or p95 > 5 s) → HALF_OPEN (probe)`.
- **DEGRADED** → weight multiplied by 0.1 (only ~5 % probe traffic); **OPEN** → excluded, probe every 10 s.
- **Recovery** requires p95 < 1.5 s for N windows (hysteresis prevents flapping).
- **Capacity spill**: vLLM's 200 RPS shifts to B (500 RPS, cheaper) first for NORMAL/LOW, A (100 RPS, faster) for HIGH. If combined headroom is exhausted → LOW priority queued/shed, HIGH served.
- Demo: mock vLLM exposes `POST /admin/latency` to inject 2.5 s latency; test asserts traffic share drops and recovers.

---

## 11. MCP / Tool execution (§6)

- **Registry**: tools registered with JSON input/output schema, `side_effecting`, `required_scopes`, timeout, retry policy, `idempotency_support`. Discovery via `GET /tools` and MCP-style `tools/list`.
- **Invocation**: `McpNode` either calls a fixed tool, or an `agent` LLM node returns a `tool_call` which the gateway executes and feeds back (bounded by `max_tool_iterations`).
- **Validation**: args validated against `input_schema` *before* the call (non-retryable error on failure); output validated after.
- **AuthZ boundary**: tenant must hold a grant with the required scopes; credentials injected by the gateway from the secret store — the LLM never sees credentials. LLM-chosen tool calls are checked against the node's allow-list (prompt-injection containment).
- **Failures**: transport/5xx → retryable; validation/4xx → non-retryable; timeout → retryable with ledger guard if side-effecting.
- **Audit**: every call → `tool_call_audit` (redacted args, args hash, outcome, latency, idempotency key, mode).

---

## 12. Dry-run / preview (§7)

| Question | Answer |
|---|---|
| How do nodes declare side effects? | `side_effecting` on node type default, overridable per node; tools declare it in registry (tool wins — can't be downgraded by the workflow author) |
| How are side effects suppressed? | `ExecutorRegistry` swaps to `MockExecutor` when `mode=DRY_RUN`; mock returns schema-valid output generated from `output_schema` or tenant-supplied fixture |
| Preview outputs | Each node's would-be request (rendered URL/body/tool args) + mock response stored in `node_output`; `GET /executions/{id}/preview` shows the full trace |
| Deterministic replay | Record mode stores every non-deterministic input (HTTP responses, LLM completions) keyed by `(node_id, request_hash)`; replay mode serves from the recording. Temporal history already makes orchestration deterministic |
| Code reuse | Everything except the final I/O call: interpreter, templating, validation, routing, budgets, tracing |

LLM nodes in dry-run: real call (non-side-effecting but costs money → counted against a dry-run budget) or fixture mode — configurable.

---

## 13. Multi-tenancy & isolation (§10)

| Control | Layer | Mechanism |
|---|---|---|
| Per-tenant rate limit | API | Redis token bucket per tenant → 429 with `Retry-After` |
| Quota | API | Daily execution counter; monthly budget |
| Concurrency limit | Dispatcher | `RUNNING` executions per tenant ≤ `max_concurrent_executions` |
| Priority / fair scheduling | Dispatcher | Deficit round-robin over per-tenant backlogs, weighted by `priority_weight`; tiers map to Temporal task-queue priority |
| Noisy-neighbour (downstream) | Activity | Per-tenant × per-provider token buckets; per-tenant share of LLM capacity |
| Cost control | Router + interpreter | Budget reservation before each LLM call |

**Tenant A submits 100,000 executions:** API accepts up to backlog cap (e.g. 10k) into `execution_queue` (202), rejects the rest with 429. Dispatcher drains A at its concurrency cap; DRR guarantees B and C get their share every round, so their latency is unaffected. A's backlog is observable (`queue_depth{tenant}`) and alertable.

Temporal Task Queue Priority & Fairness (GA May 2026) gives approximate per-tenant fairness: `fairnessKey=tenantId`, `fairnessWeight` per **tier** (overrides capped at 1,000 keys/queue), priority 1–5 per tier. It is **not a quota** and accuracy degrades with 10k keys → admission control, concurrency caps and budgets stay in our own layer (see 02-research §4).

---

## 14. Cost & safety controls (§11)

| Limit | Enforced at | Why there |
|---|---|---|
| Max fan-out / node count | Definition validation + interpreter | Reject statically when possible; cap dynamic fan-out at runtime |
| Max node executions | Interpreter (workflow state) | Only place that sees all node runs deterministically; kills runaway agent/tool loops |
| Max execution time | Temporal workflow execution timeout + interpreter check | Server-enforced even if workers are dead |
| Max retries | Activity RetryOptions + per-execution retry budget | Per-node limit alone can multiply across 50 nodes |
| Max tokens / max cost per execution | Router, **pre-call reservation** (`estimated_tokens × price`) against `budget - spent` (atomic UPDATE ... WHERE) then reconcile actual | Must stop spend *before* it happens, not detect after |
| Tenant spend (daily/monthly) | Admission control + router | Stops new work and in-flight LLM spend at the tenant level |
| Max tool iterations (agent loop) | Agent node | Bounded ReAct loop; the unbounded-loop scenario |

---

## 15. Observability (§12)

- **Tracing**: OpenTelemetry. Trace per execution (`execution_id` as trace attribute), span per node attempt, child spans for router decision, provider call, tool call. Temporal Java SDK OpenTracing/OTel interceptor propagates context through workflow → activity.
- **Metrics** (Micrometer → Prometheus), labels `tenant_tier`, `workflow_id`, `node_type`, `provider`, `model` (tenant_id only on low-cardinality aggregates / exemplars to bound cardinality):

| Metric | Type |
|---|---|
| `workflow_execution_latency_seconds` | histogram |
| `workflow_executions_total{status}` → success rate | counter |
| `node_execution_latency_seconds` | histogram |
| `llm_latency_seconds` | histogram |
| `llm_tokens_total{direction}` | counter |
| `llm_cost_usd_total` | counter |
| `node_retries_total` | counter |
| `queue_depth{tenant_tier}` + Temporal schedule-to-start latency | gauge |
| `provider_errors_total{provider,error_class}` → error rate | counter |

- **Answering "why"**: `GET /executions/{id}/trace` returns node timeline (start/end/attempts), router decisions, token/cost per node, failed external calls — joined from `node_run`, `llm_call`, `tool_call_audit`.

---

## 16. Security considerations

- Tenant ID derived from API key, enforced in every repository query (row-level security as defence-in-depth).
- Secrets resolved in activities only; redacted in audit and logs.
- SSRF protection on HTTP nodes (deny private ranges / metadata IPs, allow-list per tenant).
- Tool allow-list per node + scope grants → contain prompt-injection-driven tool misuse.
- Template rendering is non-executable (no SpEL/eval); condition expressions use a sandboxed evaluator.

---

## 17. Key trade-offs & alternatives

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| Orchestrator | Temporal (generic interpreter) | Custom Postgres engine; Restate; DBOS; Hatchet (see 02) | Durable execution/timers/signals/cancellation out of the box; time goes into the hard parts (idempotency, routing, fairness) |
| One workflow per DAG vs one per node | One interpreter workflow per execution | Child workflow per node | Small history at 50 nodes; child workflows only for fan-out > 100 |
| Language | Single Java service | Java + Python LLM worker; pure Python | One build/runtime in a 16 h box; Python worker remains a clean future split via a dedicated Temporal task queue |
| LLM framework | Thin `LlmProvider` interface | LangChain4j / LangChain | Router logic is the deliverable; frameworks hide it |
| Payload passing | By reference (Postgres) | Inline in history | Temporal payload/history limits |
| Rate limiting | Redis token buckets | Postgres counters | 500/s+ hot counters don't belong in Postgres |
