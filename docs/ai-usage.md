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
