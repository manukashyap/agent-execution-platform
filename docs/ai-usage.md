# AI usage

## How AI tools were used

- Claude Code (Opus) agents implemented each phase from `docs/07-implementation-tasks.md`. An orchestrator split the work by phase, and each phase agent got a binding brief: hard rules, files it must not touch, an exit gate, and a report format.
- Agents wrote the tests first, ran `./gradlew check` and the docker compose exit gates themselves, and recorded any deviation from the plan below.
- Facts about Temporal, Spring and the libraries were checked against the pinned artifacts (javap on the jars, and the CLI/server running in compose), not assumed from memory.

## Verified facts (P0)

Pinned versions:

- **Build:** Gradle 8.14.3, Java 21, Spring Boot 3.5.16 (from its BOM: Flyway 11.7.2, Testcontainers 1.21.4, PostgreSQL JDBC 42.7.11).
- **Temporal:** Java SDK and temporal-testing 1.40.0.
- **Test and validation libraries:** WireMock 3.13.2, ArchUnit 1.4.2, networknt json-schema-validator 1.5.9.
- **Images:**
  - Lite dev server: `temporalio/temporal:1.9.1` (CLI 1.9.1, Server 1.32.0, UI 2.54.1).
  - Full profile: `temporalio/server:1.32.0` and `temporalio/admin-tools:1.32.0`.
  - Supporting: `temporalio/ui:2.55.0`, `postgres:16`, `prom/prometheus:v3.13.4`, `redis:7-alpine`.

The six Temporal unknowns from 06 §10:

1. **`nextRetryDelay` on ApplicationFailure: CONFIRMED (SDK 1.40.0).**
   - The SDK has `ApplicationFailure.newFailureWithCauseAndDelay(msg, type, cause, Duration, ...)` and `Builder.setNextRetryDelay`.
   - `Failures.toApplicationFailure` maps `RetryableError.nextRetryDelay()` onto it, and `FailuresTest` covers this.
2. **Activity summary: CONFIRMED (SDK 1.40.0).**
   - The SDK has `ActivityOptions.Builder.setSummary(String)`.
   - It also has `WorkflowOptions.setStaticSummary` and `ChildWorkflowOptions.setStaticSummary`.
3. **`WorkflowIdConflictPolicy.USE_EXISTING`: CONFIRMED (SDK 1.40.0, Server 1.32.0).**
   - The SDK has `WorkflowOptions.Builder.setWorkflowIdConflictPolicy`.
   - On the dev server, starting `probe-1` a second time with `--id-conflict-policy UseExisting` returned the existing run instead of an AlreadyStarted error.
4. **DescribeTaskQueue backlog stats: CONFIRMED (Server 1.32.0).**
   - The command was `temporal task-queue describe -o json`.
   - The `stats[]` field returned `approximateBacklogCount` (3 after three unpolled starts), `approximateBacklogAge`, `backlogIncreaseRate`, `tasksAddRate` and `tasksDispatchRate`, per workflow and activity queue type.
   - The SDK has `DescribeTaskQueueRequest` and `TaskQueueStats` in serviceclient 1.40.0.
   - The host CLI 1.7.0 lacks some flags, so use the container CLI 1.9.1.
5. **Priority / fairness: CONFIRMED for API and persistence; scheduling behaviour NOT yet load-verified.**
   - The SDK has `io.temporal.common.Priority` with `priorityKey`, `fairnessKey` and `fairnessWeight`, set through `WorkflowOptions.setPriority` and `ActivityOptions.setPriority`.
   - The server needs dynamic config `matching.enableFairness=true`. It is set in compose for the dev server and in `deploy/temporal/dynamicconfig.yaml`.
   - `workflow describe` showed `{"priorityKey":2,"fairnessKey":"t_dev","fairnessWeight":2}`.
   - P6 must still prove fair dispatch under load.
6. **auto-setup image: FALLBACK.**
   - `temporalio/auto-setup` stopped at 1.29.x and has no 1.30+ tags.
   - The full profile instead uses `temporalio/server:1.32.0`, plus admin-tools one-shots for schema setup (`deploy/temporal/setup-schema.sh`) and namespace creation (`create-namespace.sh`).
   - Smoke-tested: the schema and namespace jobs exited 0, `temporal-pg` and Prometheus were healthy, and the UI on :8080 returned 200.

## Overrides / deviations

P0 deviations from docs/06 and docs/07:

- **Temporal wiring:** wired by hand (`engine.temporal.TemporalConfig`: WorkflowServiceStubs, WorkflowClient, WorkerFactory, Worker, plus a lifecycle bean that starts on ApplicationReadyEvent) instead of `temporal-spring-boot-starter`. This keeps the worker start explicit and lets it be switched off in tests (`aep.temporal.start-worker`).
- **json-schema-validator:** networknt pinned at 1.5.9, the latest 1.x. 3.x exists but has a different API.
- **Full profile:** auto-setup replaced with server plus admin-tools (Verified fact 6).
- **Lite dev server runs as root:** the lite Temporal container runs as `user: "0:0"`. As uid 1000 it cannot write its SQLite file onto the root-owned named volume. This is local-dev only.
- **Seam overrides:** the default seam implementations (`PassThroughSideEffectGuard`, `NoOpBudgetService`, `TypeExecutorRegistry`) are plain `@Component`s. Later phases override them with `@Primary`. `@ConditionalOnMissingBean` is unreliable on component-scanned classes.
- **API keys:**
  - No API keys are seeded in SQL. Under the `dev` profile, `DevApiKeySeeder` hashes `AEP_DEV_API_KEY` and `AEP_DEV_OTHER_API_KEY` (SHA-256) into `api_key` for `t_dev` and `t_other` with scopes `{*}`.
  - Compose defaults the keys to `aep-dev-key-t_dev` and `aep-dev-key-t_other`. bootRun has no default and logs a warning.
- **Extra V1 columns:**
  - `workflow_execution`: `priority`, `options`, `output`, `error_message`, `updated_at`.
  - `tenant_limits`: extra caps.
  - `api_key`: `revoked_at`.
  - A composite FK from `workflow_execution` to `workflow_definition`.
- **App port:** 8000. Mocks run on 8090, and the full-profile Temporal UI on 8080.
- **Dockerfile:** one multi-stage Dockerfile, parameterised by `ARG MODULE` (app or mocks).
- **OutboundClient 4xx handling:** every 4xx except 429 becomes NonRetryable `UPSTREAM_CLIENT_ERROR`, including 409. P5 may need to treat 409 "request in flight" as retryable `EFFECT_IN_PROGRESS`.
- **SSRF / DNS rebinding gap:** `EgressPolicy` resolves the host and checks every address, but the HTTP client resolves again on connect. Pinning the resolved IP is deferred.
- **OTel:** trace propagation on outbound calls is deferred to P8.

P5 deviations (side-effect ledger, T5.1/T5.2/T5.4):

- **Ledger `response jsonb` column:** added so a COMMITTED row can return the stored result. `response_ref` is kept but unused.
- **7th seeded tool:** `crm.delete` (RETRIABLE, NATIVE_KEY), added as the compensation for `crm.upsert`, because the registry has a self-FK and a CHECK requiring COMPENSATABLE tools to name an inverse. The mocks need `POST /crm/delete {external_ref}`.
- **Live lease means in progress for any owner:** this includes the same attempt number. An attempt number cannot tell a re-entry from a concurrent duplicate, so the row is never re-taken while its lease is live.
- **Provider 409 handling:** `OutboundClient` now throws `common.http.UpstreamClientError`, a subclass of NonRetryable `UPSTREAM_CLIENT_ERROR`, which carries the status and body. The guard maps a 409 whose `error` is absent or `"in_progress"` to retryable `EFFECT_IN_PROGRESS` for the remaining lease and leaves the row unchanged.
- **Retryable failures by kind:**
  - 429 deletes the PENDING row, because the provider did nothing. Otherwise a NONE-mode tool would become NEEDS_ATTENTION just for being rate-limited.
  - 5xx expires the lease at once, so the next attempt reconciles right away.
  - Timeouts and IO errors keep the lease, because the call may still land.
- **FAILED on entry:** throws NonRetryable `UPSTREAM_CLIENT_ERROR` without calling the provider. Taking ownership of an UNKNOWN row moves it back to PENDING.
- **`ErrorCodes.NEEDS_ATTENTION`:** added to `common`.
- **`EffectSpec` key check:** the record now verifies that its key equals `EffectKey.of(identity)`. Build it with `EffectSpec.of(...)`.
- **`CompensationReconciler.decide`:** takes the forward tool's `Reversibility`, because the ledger stores no tool name and PIVOT detection needs it. It also takes an optional forward `EffectCall`, used only for `lookup()`.
- **Reconciler states:**
  - A live forward lease throws `RetryableError(EFFECT_IN_PROGRESS)` instead of returning a decision.
  - A NATIVE_KEY forward with an unresolved outcome returns `RECONCILE_FORWARD`: the engine re-runs the forward through the guard, then decides again.
  - A LOOKUP forward that is not found returns SKIP and leaves the row as it is.
P3 (router) deviations and assumptions:

- **`LLM_UNAVAILABLE`:** added to `common.ErrorCodes`. Raised as retryable when the primary and every fallback failed within one attempt.
- **`llm_call` (V3):**
  - No FKs to `workflow_execution`, so the dev debug endpoint can record calls.
  - An extra `seq` column: 0 = primary, 1..2 = fallbacks within one attempt.
  - Rows are written by the router (one per provider call), not by the node executor.
- **Capabilities:** vLLM is configured without `tools` (chat, json only), so tool-calling turns route to A/B.
- **NORMAL weights:** latency 0.35, cost 0.45, errors 0.2. They lean slightly to cost so A and B don't tie and B wins, matching the 01 §9 spill direction. HIGH is 0.7/0.1/0.2 and LOW is 0.1/0.7/0.2. The DEGRADED penalty is +1.0.
- **Latency term:** HEALTHY providers are scored on their configured nominal latency, not the observed p95. Scoring on the observed p95 moved traffic away after a single slow window and starved the tracker of the samples it needs to ever reach DEGRADED. The observed p95 is scored only for DEGRADED providers. The error rate is always the observed one; it ages out with the 6-window lookback.
- **HALF_OPEN → HEALTHY:** happens after 10 probes when at most 2 failed and the probe p95 is ≤ 2 s; otherwise the provider goes back to OPEN. This decision rule is an assumption; the spec says only "by outcome".
- **Model hint:** `model` in the llm config is a soft preference (reason `model_hint`), honoured only when an eligible provider serves exactly that model.
- **Recovery needs ≥ 40 req/s total:** recovery requires 3 windows with ≥ 20 samples each. While DEGRADED, vLLM gets only the 5 % probe share, so total traffic must be at least 20 / (0.05 × 10 s) = 40 req/s. `scripts/vllm-degradation.sh` therefore defaults to 50 req/s, not 5.
- **Debug endpoint:** `/internal/router/complete` and `/internal/router/state` exist only under `@Profile("dev")`, to drive the script until the engine runs llm nodes.
- **Compose:** the app container needs `AEP_LLM_A_URL`, `AEP_LLM_B_URL` and `AEP_VLLM_URL` pointing at `http://mocks:8090/llm/...`. The yml defaults target localhost.
P1 deviations from docs/06 and docs/07:

- **PDF example fixture:** `fixtures/pdf-example.json` is a reconstruction of the PDF §4 example; it is posted unchanged in the contract tests.
- **Definition model additions:** optional `max_parallel` and `schedule_to_close_s` fields, `ToolInfo.compensation`, and OR semantics for `NodeTraits`.
- **New error codes:** `VERSION_EXISTS` (409, a re-publish with a different body) and `NOT_IMPLEMENTED` (501, for `/preview`, `/trace` and REPLAY mode).
- **Package placement:**
  - `DryRunOptions` stays in `nodes`; ArchUnit's determinism rule allows it explicitly.
  - `ExecutionService` lives in `execution.service`, because `execution` is a pure contract package.
- **ApiKeyRepository.findActive:** looks up by `key_hash` and is the one query not filtered on tenant_id. The tenant is unknown until the key resolves.
- **Error handling:** the global `@RestControllerAdvice` also shapes errors from P3's `/internal/router` controller.
- **Per-test tenants:** API ITs create random tenants (`TestTenants`), so the shared container accumulates tenants. `CoreSchemaIT` now asserts that the seeds are *contained* rather than an exact list.
- **GET /v1/tools** (06 §4.2) is not implemented in P1.
- **Stub workflow:** `StubDagInterpreterWorkflowImpl` does not write DB status, so GET shows QUEUED until P2a.
- **Cancel:** describes the run first and only sends a cancel when the run is RUNNING. If there is no open run, the row is CAS'd to CANCELLED directly.
- **Start retries:** 3 inline attempts with linear backoff (`aep.engine.launcher.*`). After that the row becomes START_FAILED and the API returns 503 with Retry-After.
- **Open:** a replay of an Idempotency-Key whose execution is START_FAILED returns that dead execution id. If Temporal actually started but the client saw a failure, the row says START_FAILED while the run exists; reconciliation is left to a later phase.

P4 (tools / MCP) deviations and assumptions:

- **Credentials:** `ToolCredentialProvider` reads `AEP_TOOL_CRED_<TENANT>_<TOOL>`. Tenant and tool are upper-cased, and every character outside `[A-Z0-9]` becomes `_`. If that variable is unset, it falls back to `aep.tools.dev-credential` (`AEP_TOOL_DEV_CREDENTIAL`, empty by default, in which case no header is sent). The credential is sent only as `Authorization: Bearer` on the MCP call. It never appears in args, node output, audit, logs or prompts, and `ToolsProperties.toString` masks it.
- **`tool_call_audit` (V4):** has no FKs, matching `llm_call`. There is one row per attempt. Args are stored only as a SHA-256 of key-sorted canonical JSON. `effect_key` is set only for side-effecting tools. An audit write failure is logged and never masks the tool result.
- **READ_ONLY tools bypass the effect ledger.** Side-effecting tools go through `LedgerSideEffectGuard`. LOOKUP reconcile renders the registry lookup spec against `{args}`, and treats the effect as "found" when the first array field of the lookup result is non-empty. This heuristic is an assumption.
- **MCP error mapping:**
  - `-32602`/`-32600` → `VALIDATION_FAILED`.
  - `-32601` → `TOOL_NOT_FOUND`.
  - `isError` with `in_progress`, or 409 → `EFFECT_IN_PROGRESS` (retryable).
  - 429 → `UPSTREAM_RATE_LIMITED`.
  - Other 4xx → `UPSTREAM_CLIENT_ERROR`.
  - Anything else → `UPSTREAM_UNAVAILABLE` (retryable).
- **mocks `crm.delete`:** returns 200 with `deleted:false` when it is repeated (idempotent).
- **LLM tool round:**
  - Only READ_ONLY tools may be offered. A side-effecting tool in `tools`, or a model asking for an unlisted tool, → `TOOL_FORBIDDEN`.
  - `maxToolCalls` defaults to 1 and must be 1–3, otherwise `VALIDATION_FAILED`. A model exceeding it → `TOOL_CALL_LIMIT`.
  - Tool output goes back to the model as a `role:tool` message `{"untrusted":true,"tool":..,"data":..}`, truncated to 16k chars, behind a system guard message.
  - Tool names are sent verbatim, dots included. The mocks accept this; real OpenAI-style providers may need name mapping.
  - Tool calls cost 0 against the budget; budget is charged per LLM turn by the router.
  - In-round tool calls reuse the node's `callIndex` in the audit. They are READ_ONLY, so they have no ledger key.
- **Not done here (outside P4 ownership):** a definition-validator rule to reject llm nodes whose `tools` include non-READ_ONLY tools or whose `maxToolCalls` is outside 1–3. Today this is enforced at runtime only. `/v1/tools` relies on the P1 auth filter populating `TenantContext`, and returns 401 when it is absent.

P7 (tenancy and cost, T7.1–T7.3) deviations and assumptions:

- **Extra V5 table `execution_budget`:** docs/06 lists only `tenant_budget` and `budget_reservation`. A per-execution counter row (`limit_usd`, `reserved_usd`, `spent_usd`) makes the per-execution cap atomic under concurrent `forEach` reservations, using the same conditional `UPDATE … WHERE spent+reserved+amt <= limit` as the tenant row. It has no FK to `workflow_execution`.
- **`node_id` / `call_index` parsed from the ref:** the `BudgetService` interface is unchanged; the router's ref `llm:{nodeId}:{callIndex}:…` is parsed into those columns (null if unparseable).
- **Execution cap derivation (SQL, lazily on the first reservation):**
  - Uses the definition's `limits.max_cost_usd` (or `maxCostUsd`) when set.
  - Otherwise, for DRY_RUN, `LEAST(tenant ceiling, aep.cost.dry-run-max-cost-usd = 0.50)`.
  - Otherwise the ceiling, which is `tenant_limits.max_cost_usd`, falling back to `aep.cost.fallback-execution-max-cost-usd = 5`.
- **Tenant budget:** the row is created lazily at `aep.cost.default-tenant-budget-usd = 100`, MONTHLY. There is no period rollover job and no stale-reservation reaper; both are design-only.
- **Confirm/cancel:** a late confirm after a cancel still records the spend, without releasing twice. Confirming an unknown or already-confirmed id is a no-op (a warn is logged for a non-UUID id).
- **Admission:**
  - The token bucket is per replica (in-memory), so the effective rate is the configured rate × the number of replicas.
  - The rate is checked before the concurrency cap, so a request rejected by the cap still consumes a token.
  - The concurrency cap is soft (a derived `count(*)` of live executions). Overshoot is bounded by the number of concurrent admitters.
  - Tenant limits and tiers are cached for 30s (`aep.tenancy.limits-refresh`). A missing `tenant_limits` row uses the `aep.tenancy.default-*` values.
- **Temporal priority:**
  - The tier sets the base priority key: ENTERPRISE 2, STANDARD 3, FREE 4. The execution's priority shifts it: HIGH −1, LOW +1. The result is clamped to 1–5.
  - `fairnessKey` = tenant id. `fairnessWeight` is ENTERPRISE 4, STANDARD 2, FREE 1.
  - `TemporalExecutionLauncher` keeps its 4-arg constructor, which uses `TemporalPriorityPolicy.uniform()`; Spring uses the 5-arg one.
- **Test fixture:** `LlmExecutorIT` now inserts its tenant row, because budget rows have an FK to `tenant`.
- **Load tests:** new tenants get `rate_per_sec` 10 / `burst` 20 by default. Load-test tenants need their `tenant_limits` raised (P9).

P8 (observability, T8.1/T8.2) deviations and assumptions:

- **No tenant_id label anywhere.** The tier comes from `CachedTenantTiers`, the same 30s cache the priority policy uses. `MetricsPrometheusIT` scans `/actuator/prometheus` to check this. The per-tenant view is the `/trace` endpoint.
- **queue_depth** is a gauge refreshed every `aep.observability.queue-depth.refresh` (10s) by `QueueDepthMonitor`. It has a `source` label and no tenant_tier:
  - `temporal_workflow` and `temporal_activity`: DescribeTaskQueue backlog, 2s deadline.
  - `admission_queued`: a global `count(*)` of QUEUED executions. This is a deliberate cross-tenant query; it returns no tenant data.
  - Refresh failures keep the last value. The first failure is warned; later ones are logged at debug.
- **Extra meters beyond the PDF list:**
  - `tool_call_latency{provider=<tool>, outcome}`
  - `schedule_to_start{tenant_tier}`
  - `admission_rejections{tenant_tier, reason}`
  - `budget_rejections{tenant_tier, reason}`
- **Tool labels:** tools go in the `provider` label. LLM and tool errors share `provider_errors_total{provider, error_class}`. The tool label is set only after authorisation, so an unvetted tool name never becomes a label.
- **side_effect_unknown** counts only the PENDING→UNKNOWN transition in the guard. The reconciler's NEEDS_ATTENTION is not counted.
- **Engine-owned meters:** `executionCompleted`, `nodeCompleted`, `nodeRetried`, `compensation` and `scheduleToStart` are exposed on `AepMetrics`. Their call sites live in engine.activity / execution.*, which are owned by the P2 agent.
- **Trace endpoint:**
  - It replaces the P1 501 stub. It reads all its tables in one REPEATABLE READ read-only transaction.
  - Nodes are keyed by (nodeId, callIndex, phase). LLM calls attach to FORWARD.
  - Node types come from the pinned definition spec; anything not in it is `unknown`.
  - `llmFallbacks` counts calls with seq > 0.
  - Budget totals are the per-execution reservation sums (reserved = still RESERVED).
- **`TemporalTaskQueueBacklog`** has no dedicated test. The DescribeTaskQueue API was verified in T0.7.

### P2a / P2b (DAG interpreter, linear saga)

- **Commit split:** T2a.4 and T2a.6 landed inside the T2a.2 commit. The stub workflow deletion is in T2a.1.
- **Engine ITs use scripted executors:** `DagInterpreterIT` drives interpreter semantics (timing, retries, fatal errors) through the test-only `ScriptedExecutor` under the `mcp` type label. `SagaIT` uses the real `http` executor and `BudgetCapIT` the real `llm` executor (`test.real-node-types`). The real `McpToolExecutor` is covered by the P4 tests; it cannot script sleeps or failure counts, so the scripted fallback stays (test scope only, nothing in main).
- **on_failure CONTINUE:** a run whose only failures are CONTINUE nodes ends SUCCEEDED.
- **START_FAILED:** after a successful relaunch, the earlier error_code and ended_at stay on the row (the CAS COALESCEs them).
- **Cancel:** the SDK closes a cancel-requested run as CANCELED even when the workflow returns normally. The DB row is the source of truth. `ApiContractIT`'s cancel expectation was adjusted to match.
- **Saga triggers:** cancel and timeout also compensate, not only FAILED.
- **Nothing to undo:** when every saga step is SKIPPED, the run keeps its original status (e.g. FAILED), even though the row passed through COMPENSATING.
- **Compensation retries:** they use the node's retry policy when max_attempts > 1, otherwise 6 attempts (500 ms, x2, 30 s cap).
- **Reconcile-forward:** a RECONCILE_FORWARD re-run does not record a node_run. It is bounded at 2 rounds and then becomes NEEDS_ATTENTION. LOOKUP reconciliation uses a no-lookup EffectCall.
- **Activity result enum:** `CompensationActivity.Result` mirrors `CompensationDecision.Kind` so `engine.workflow` never imports `sideeffect`. The ArchUnit rules are unchanged.
- **No new columns:** V1 has no cost or token columns, so the per-node cost lives in `NodeOutputRef` only. The execution output is the sink nodes' outputs, or `{"$stored":"node_output"}` when they are too large.
- **Orphaned RUNNING rows:** a worker kill mid-attempt leaves that attempt's RUNNING `node_run` row. The next attempt writes its own row.
- **Engine metric call sites (`ActivityTelemetry`):**
  - `executionCompleted` fires on an applied terminal CAS; its latency is measured from started_at.
  - `nodeCompleted` fires on success, or on a failure that is final (non-retryable, cancelled, or the last attempt). Its latency is measured from the first schedule.
  - `nodeRetried` and `scheduleToStart` fire at activity entry.
  - `compensation` fires per step: COMPENSATED becomes SUCCEEDED; NEEDS_ATTENTION, PIVOT_EXECUTED or a final failure become FAILED; SKIPPED is not counted.

### Checkpoint A fixes

- **node_run closes exactly once (supersedes "Orphaned RUNNING rows" above):** a row leaves RUNNING once; later writes to it are no-ops. A new attempt closes earlier RUNNING attempts as FAILED/`TIMEOUT`. When an attempt fails without an ApplicationFailure (StartToClose, heartbeat timeout, worker death), the interpreter closes the node's RUNNING rows. An attempt whose heartbeat finds it gone (`ActivityNotExistsException`) records FAILED/`TIMEOUT`, not CANCELLED.
- **START_FAILED reconciled (resolves the "Open" START_FAILED note above):** the first workflow transition accepts QUEUED or START_FAILED → RUNNING, so a run that Temporal accepted while the client saw an error moves the row forward by itself. On "already started", the launcher describes the run. If the run is open, the start succeeds. If the run has closed, the replay records it on the row as terminal (TIMED_OUT, CANCELLED, otherwise FAILED) with `error_code=START_FAILED`, instead of leaving the row QUEUED. The response is then that row, not a 503.
- **Budget reservation reaper:** `cost.BudgetReservationReaper` runs every `aep.cost.reaper.interval` (default 60 s). Each run cancels up to `batch-size` (100) RESERVED rows older than `max-age` (15 min) through `BudgetService.cancel`, which releases both counters. A confirm that arrives after that still records the spend. Its scan is the one cross-tenant read in `cost`; each cancel uses the row's own `tenant_id`.

### P6 T6.3 notes

- `scripts/dry-run.sh` accepts `BASE_URL`/`MOCKS_URL` (falling back to `AEP_BASE_URL`/`AEP_MOCKS_URL`). When several app instances share one Temporal, give each its own `AEP_TEMPORAL_TASK_QUEUE`; with the default `aep-main` another instance's worker can steal the activities and run them with its own egress allow-list and older code.

### P10 T10.2 / T10.4 (replay half) notes

- **Architecture diagram:** `docs/architecture.svg` is hand-written SVG (no fonts or scripts), referenced from design.md section 1. The Mermaid block stays as a text fallback. The Temporal server box and the fairness key are drawn from `TemporalPriorityPolicy`; the diagram is a logical view, not a deployment view.
- **Replay tests:** `WorkflowReplayIT` replays two committed histories (`app/src/test/resources/histories/`: happy path with condition + forEach, and a saga that compensates) with `WorkflowReplayer` against `DagInterpreterWorkflowImpl`. It needs no Postgres or Temporal server.
- **Re-recording:** `AEP_RECORD_HISTORIES=true ./gradlew :app:test --tests '*Record*HistoryIT'` (an environment variable, because Gradle does not forward `-D` flags to the test JVM). The recorders are skipped otherwise. Re-record only after a deliberate, reviewed workflow change; a replay failure on an unchanged recording means in-flight runs would break, so gate with `Workflow.getVersion` instead.
### T5.5 engine-level side-effect tests, failure walkthrough, vLLM run

- **Scaled timing in `SideEffectEngineIT`:** the PDF §9 timing (provider 15 s, timeout 10 s) runs as provider 3 s, `timeout_s` 2 (client gives up at 1 s, lease ends 7 s after the attempt began), on real time because the lease comes from the app clock, which Temporal time-skipping cannot advance. The full-size timing is exercised by `scripts/failure-walkthrough.sh`. Test 4 is `SagaIT`.
- **UNKNOWN effect surfaces as NEEDS_ATTENTION:** a `NEEDS_ATTENTION` activity error closes the node_run as NEEDS_ATTENTION (`NodeActivityImpl.failedStatus`) and the interpreter aborts the execution as NEEDS_ATTENTION (`ForwardRun.statusOf`). A saga step whose forward outcome is unknown ends the execution NEEDS_ATTENTION, not COMPENSATION_FAILED (`SagaRun`). Required by tests 3 and 5; the previous code reported FAILED / COMPENSATION_FAILED.
- **Walkthrough status parsing:** the execution JSON nests `status` fields (node bodies), so the script reads the one after `version`. A worker kill at ~12 s lands after attempt 1 timed out at 9 s, so it hits the lease wait, not the in-flight call.
- **vllm-degradation default RATE 50 to 120:** the curl-per-request driver delivered about 35 req/s when asked for 50, so DEGRADED probe windows held 18 samples (< 20) and vLLM never recovered. At 120 it delivers about 55 req/s.
- **Shared Temporal:** script runs used `AEP_TEMPORAL_TASK_QUEUE=aep-p5-walkthrough` so other worktrees' workers could not pick up the activities.

### P9 T9.1-T9.3 load test notes

- **Isolated stack:** `loadtest/run.sh` uses compose project `aeplt` (fresh volumes, removed by `run.sh down`) so it never touches a shared `aep` stack. `loadtest/compose.override.yml` only adds `pg_stat_statements` to Postgres; `compose.yml` and app defaults are unchanged. The three load-test tenants get raised `tenant_limits` / `tenant_budget` through `loadtest/seed.sql`, with random API keys per run (kept in git-ignored `loadtest/out/keys.env`, stored hashed).
- **Workflow shape:** the forEach node is an http call (CRM mock) rather than an LLM call, because router provider rps (llm-a 100, llm-b 500, vllm 200) would cap a 10x fan-out of LLM calls at about 80 executions/s and the test would measure the router, not the engine. One LLM node (`classify`) per execution keeps the router/budget path in the picture.
- **Outcome:** the saturation knee is about 25 executions/s on this laptop, limited by Temporal + its Postgres, not by the app. Details and the 10x plan are in `loadtest/RESULTS.md`; raw captures are in `loadtest/results/`.
- **Flake gate:** `SideEffectEngineIT` passed 5 of 5 consecutive runs (4 tests each, no flake, no change made).
