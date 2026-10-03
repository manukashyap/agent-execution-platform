# Agent Execution Platform

A multi-tenant platform that runs customer-defined workflow DAGs of `http`, `llm`, `mcp` (tool) and `condition` nodes durably. Java 21, Spring Boot 3.5, Temporal (one generic interpreter workflow per execution), Postgres 16 (state, ledger, audit), mock external services in `mocks/`.

What it demonstrates: a DAG engine with retries, timeouts, cancellation and a saga; an effectively-once side-effect ledger; an LLM router with health-based degradation and spill; MCP-style tool execution; dry-run with preview; per-tenant admission, budgets and Temporal priority; metrics and an end-to-end trace.

| Read this | |
|---|---|
| [docs/design.md](docs/design.md) | The design document (architecture, execution model, failure semantics, scaling, tenancy, router, trade-offs) |
| [docs/architecture.svg](docs/architecture.svg) | Architecture diagram |
| [loadtest/RESULTS.md](loadtest/RESULTS.md) | Load-test results. The numbers live there; they are not repeated in this README |
| [docs/ai-usage.md](docs/ai-usage.md) | AI-tool usage, verified facts and every override of the plan |
| [docs/06-execution-plan.md](docs/06-execution-plan.md), [docs/07-implementation-tasks.md](docs/07-implementation-tasks.md) | Authoritative spec and task breakdown (docs 01-05 hold the rationale) |

## Run it (one command)

Needs Docker only.

```bash
docker compose --profile lite --profile app up -d --build
```

Compose profiles (`compose.yml`):

| Profile | Starts |
|---|---|
| `lite` | Postgres 16 (`:5432`), Temporal dev server (`:7233`, UI `:8233`), mocks (`:8090`) |
| `full` | Postgres, Temporal on Postgres (`:7233`), Temporal UI (`:8080`), Prometheus (`:9090`), Redis (`:6379`), mocks. Use this for load tests |
| `app` | The platform container on `:8000`. Combine it with exactly one of `lite` / `full` |

Ports: **app 8000**, **mocks 8090**. Health: `http://localhost:8000/actuator/health`; metrics: `/actuator/prometheus`.

The app container runs with the `dev` profile and API keys that default to `aep-dev-key-t_dev` (tenant `t_dev`) and `aep-dev-key-t_other` (tenant `t_other`); override with `AEP_DEV_API_KEY` / `AEP_DEV_OTHER_API_KEY` in your shell. Then:

```bash
export AEP_DEV_API_KEY=aep-dev-key-t_dev
scripts/happy-path.sh          # publishes the PDF example and runs http -> llm -> condition -> http
```

Full stack with Prometheus and Temporal UI: `docker compose --profile full --profile app up -d --build`. Stop with `docker compose --profile lite --profile app down` (add `-v` to drop data).

## Dev path (bootRun, lite profile)

```bash
export JAVA_HOME=<a JDK 21>
# optional: defaults to aep-dev-key-t_dev (tenant t_dev), as in compose
./gradlew :app:bootRun
```

- `bootRun` defaults to the Spring profile `dev` (override with `SPRING_PROFILES_ACTIVE`) and runs from the repo root.
- `spring-boot-docker-compose` starts `compose.yml` with the `lite` profile automatically (`lifecycle-management: start-only`, so containers keep running when the app stops). That brings up Postgres, the Temporal dev server and the mocks. The mocks can also be run from source: `./gradlew :mocks:bootRun`.
- Flyway migrates the schema (`V1` to `V6`) on startup. The worker runs inside the app (`aep.temporal.start-worker`).
- If several checkouts share one Temporal server, give each its own queue (`AEP_TEMPORAL_TASK_QUEUE=<name>`, default `aep-main`), otherwise another instance's worker can pick up your activities.

### How to get an API key

There is no key-issuing endpoint. Keys are stored only as SHA-256 hashes in `api_key`. Under the `dev` profile, `DevApiKeySeeder` runs at startup and hashes the plaintext from `AEP_DEV_API_KEY` (tenant `t_dev`) and `AEP_DEV_OTHER_API_KEY` (tenant `t_other`, for isolation checks), inserting them with scope `*`. Both `bootRun` and compose default to `aep-dev-key-t_dev` / `aep-dev-key-t_other`; set `AEP_DEV_API_KEY` / `AEP_DEV_OTHER_API_KEY` to choose your own, and send the key back as a bearer token. Outside the `dev` profile no key is seeded, so these defaults never reach a non-dev deployment.

```bash
curl -H "Authorization: Bearer $AEP_DEV_API_KEY" http://localhost:8000/v1/tools   # or: -H "X-API-Key: ..."
```

The `none`-mode tool scenario in `failure-walkthrough.sh` also needs `AEP_TOOL_DEV_CREDENTIAL` set to any non-empty value (the mocks accept it). Per-tenant tool credentials use `AEP_TOOL_CRED_<HEX(tenant)>_<HEX(tool)>`, where each part is the upper-case hex of its UTF-8 bytes, so distinct ids never collide (`t_dev` + `leads.fetch` is `AEP_TOOL_CRED_745F646576_6C656164732E6665746368`). The `AEP_TOOL_DEV_CREDENTIAL` fallback is used only under the `dev` profile; in any other profile, a missing per-tenant credential fails the call.

### Tests and coverage

```bash
./gradlew check                 # unit, integration (Testcontainers Postgres), WireMock, ArchUnit, replay tests
./gradlew jacocoTestReport      # build/reports/jacoco/test/ in app/ and mocks/ (html + xml)
```

Docker must be running for the integration tests. `AEP_RECORD_HISTORIES=true ./gradlew :app:test --tests '*Record*HistoryIT'` re-records the workflow replay histories (only after a deliberate workflow change).

## API overview

All routes are under `/v1`, require `Authorization: Bearer <key>` (or `X-API-Key`) and return an envelope `{data, error, meta}`. A key carries scopes (`*` grants all); a missing scope is `403 FORBIDDEN`, a bad key `401`.

| Endpoint | Scope | Notes |
|---|---|---|
| `POST /v1/workflows` | `workflows:write` | Publish a definition (the assignment's JSON verbatim). 201 new, 200 identical re-publish, 409 `VERSION_EXISTS` for a changed body under the same version. `meta.warnings` carries validator warnings |
| `GET /v1/workflows/{id}/versions/{version}` | `workflows:read` | Pinned definition |
| `GET /v1/tools` | `workflows:read` | Tools granted to the tenant, with reversibility and idempotency mode |
| `POST /v1/workflows/{id}/executions` | `executions:write` | 202 + execution id. Optional `Idempotency-Key` header (repeat returns the original execution, `meta.replayed=true`). Body: `input`, `version`, `mode` (`LIVE` / `DRY_RUN`), `dryRun{mockLlm, allowReadOnly}`, `priority` |
| `GET /v1/executions/{id}` | `executions:read` | Status and output |
| `GET /v1/executions/{id}/nodes` | `executions:read` | Per-attempt node runs |
| `GET /v1/executions/{id}/trace` | `executions:read` | Timeline joining node runs, LLM calls (provider, router reason, tokens, cost) and tool audit |
| `GET /v1/executions/{id}/preview` | `executions:read` | Dry-run preview: outputs, mocked calls with rendered requests, compensation plan |
| `DELETE /v1/executions/{id}` | `executions:write` | Asynchronous cancel, 202; saga compensation runs if needed |

Dev-only (profile `dev`): `POST /internal/router/complete` and `GET /internal/router/state`, used by `vllm-degradation.sh`.

Errors map to HTTP statuses (`ApiExceptionHandler`): admission failures `RATE_LIMITED` and `CONCURRENCY_LIMIT` are `429` with `Retry-After`; a failed Temporal start is `503` with `Retry-After`; validation errors are `4xx` with the validator's codes. Prometheus metrics: `GET /actuator/prometheus` (not tenant-labelled; the per-tenant view is `/trace`).

Node types: `http`, `llm`, `mcp`, `condition`; parallelism comes from the DAG (`depends_on`) and `forEach` fan-out with a concurrency cap.

## Demo scripts (`scripts/`)

Each prints its own checks. They use `AEP_DEV_API_KEY` (default `aep-dev-key-t_dev`, matching the app's dev default), and honour `AEP_BASE_URL` / `BASE_URL` (default `http://localhost:8000`) and `AEP_MOCKS_URL` / `MOCKS_URL` (default `http://localhost:8090`). Workflow nodes are resolved by the app, so against the compose `app` container the scripts publish `http://mocks:8090` URLs automatically (override with `AEP_WF_MOCKS_URL`). Run the stack first (compose `lite` plus the app, or the dev path).

| Script | What it proves |
|---|---|
| `happy-path.sh` | The PDF §4 example JSON is accepted unchanged; an `http -> llm -> condition -> http` workflow runs end to end against the mocks, with node runs printed |
| `saga-charge-then-send.sh` | Charge (compensated by a refund) then a send that is forced to fail: the execution ends `COMPENSATED` with exactly one charge and one refund. `VARIANT=refund-fails` fails every refund: bounded retries, then `COMPENSATION_FAILED` with no refund |
| `dry-run.sh` | The PDF §7 lead workflow in `DRY_RUN`: the read-only fetch and the LLM classify run live (one mock hit each); the CRM write and the send are mocked (zero hits); prints `/preview` and checks the mock call counters. `MOCK_LLM=true` mocks the classify too |
| `failure-walkthrough.sh` | The PDF §9 timing (provider answers at 15 s, node timeout 10 s) under both idempotency modes, plus a worker kill, a dropped connection and a 429 with `Retry-After`. `native_key`: the re-call returns the stored charge, one charge; `none`: no second call, `NEEDS_ATTENTION`, one send; `worker_kill`, `drop`, `rate_limit`: still one charge. Select with `SCENARIOS`; set `RESTART_CMD` so the script can restart the app for `worker_kill`. Takes several minutes |
| `vllm-degradation.sh` | Router health: steady traffic, vLLM made slow (3 s), router marks it `DEGRADED` and moves traffic to llm-b with a 5 % probe share, then latency restored and vLLM recovers through the probes. Needs about 40 req/s total for recovery, which is the default `RATE=40`; the driver batches requests per tick with `curl --parallel` and prints the delivered rate with every poll. `RATE=5` shows only the degradation half |

The mocks expose `/admin/*` hooks (latency, fail rate, rate limit, drop connection, reset, call counts) that these scripts drive.

**Load test.** `./loadtest/run.sh` brings up the `full` + `app` profiles (project `aeplt`), seeds 3 tenants with raised limits, runs the k6 ramp (about 10 min) and writes captures under `loadtest/out/`; `./loadtest/run.sh down` removes the stack. Method, numbers and the 10× plan are in [loadtest/RESULTS.md](loadtest/RESULTS.md).

## Assumptions

Collected from [docs/06](docs/06-execution-plan.md), [docs/design.md](docs/design.md) and [docs/ai-usage.md](docs/ai-usage.md); each is a deliberate choice, not an oversight.

- **Implicit ordering:** a node without `depends_on` depends on the previous node in list order; `depends_on: []` marks an explicit root (design §2).
- **Admission rejects, it does not queue:** over the rate or concurrency cap the API answers `429` with `Retry-After` and stores nothing. The production design is a per-tenant backlog drained by deficit round-robin; the prototype implements the rejecting variant (design §7, §13).
- **"Run Again" creates new effects:** a new POST gets a new execution id, so new effect keys, so new charges or sends, by design. An optional `business_key` dedupe is designed, not built (design §5).
- **Effectively-once, not exactly-once:** at-least-once activities plus stable idempotency keys plus a side-effect ledger with an explicit `UNKNOWN` state that is never blindly retried; unresolved outcomes end `NEEDS_ATTENTION`. "Rollback" is semantic compensation (design §5).
- **Idempotency scope:** the same `Idempotency-Key` per tenant returns the original execution.
- **Definitions are pinned:** an in-flight run keeps its definition version; new executions use the new one. Workflow code changes go through `Workflow.getVersion`, guarded by two replay tests.
- **Limits:** soft concurrency cap (a derived count), per-replica in-memory token bucket (the effective rate scales with replica count), tenant limits cached 30 s; new tenants default to 10 req/s, burst 20.
- **Budgets:** per-execution cost caps are atomic; the tenant budget is created lazily (default 100 USD, monthly) with no period rollover job. Dry-runs default to a 0.50 USD cap.
- **Tools:** the LLM may call only `READ_ONLY` tools, at most 1 to 3 per node (`maxToolCalls`); tool output is returned to the model marked untrusted. Side-effecting tools run only through the ledger.
- **Router:** vLLM is configured without `tools`, so tool-calling turns go to llm-a/llm-b. Healthy providers are scored on configured nominal latency; the half-open recovery rule is our own assumption.
- **SSRF:** `OutboundClient` is the only outbound HTTP path and enforces an allow-list (`AEP_OUTBOUND_ALLOW_HOSTS`) and a self-host deny-list; the HTTP client resolves each host once and connects only to the validated addresses (no DNS-rebinding window), and redirects are never followed. Each call has a hard deadline and a response body cap (`aep.outbound.max-response-bytes`, `AEP_OUTBOUND_MAX_RESPONSE_BYTES`, 10 MiB). The connection pool holds 400 connections, 200 per route (`AEP_OUTBOUND_MAX_CONN_TOTAL`, `AEP_OUTBOUND_MAX_CONN_PER_ROUTE`); a pool wait (`connection-request-timeout`, 1 s) fails as retryable `UPSTREAM_NOT_SENT`.
- **PDF §4 fixture** (`app/src/test/resources/fixtures/pdf-example.json`) is a reconstruction of the assignment example.

## AI-tool usage

Claude Code agents implemented each phase of the plan. An orchestrator split work by phase, and each agent received a binding brief: hard rules, files it must not touch, an exit gate and a report format. Agents wrote tests first, ran `./gradlew check` and the compose exit gates themselves, and recorded every deviation. Facts about Temporal, Spring and the libraries were checked against the pinned artifacts (`javap` on the jars, the CLI and server in compose), not taken from memory; the six Temporal unknowns from the plan were each confirmed or given a fallback.

Where the plan was overridden (full list with reasons in [docs/ai-usage.md](docs/ai-usage.md#overrides--deviations)):

- Temporal is wired by hand, not through `temporal-spring-boot-starter`, so the worker start is explicit and switchable in tests.
- The `full` profile uses `temporalio/server` plus admin-tools one-shots instead of `auto-setup`, which stops at 1.29.x. Fairness scheduling under load is not yet verified.
- The lite dev server runs as root so it can write its SQLite file on the named volume (local only).
- No seeded API keys in SQL; the `dev` profile seeder hashes keys from environment variables.
- Provider 409 "in progress" is retried (`EFFECT_IN_PROGRESS`); 429 releases the ledger row; 5xx expires the lease; timeouts keep it. Leases use the database clock. An unknown effect surfaces as `NEEDS_ATTENTION`, not `FAILED` / `COMPENSATION_FAILED`.
- Extra tables and columns beyond the spec: `execution_budget`, ledger `response`, a seeded `crm.delete` compensation tool.
- The PDF §9 timing tests are scaled in the suite (3 s provider, 2 s timeout); the full-size PDF timing runs in `failure-walkthrough.sh`.
- `vllm-degradation.sh` default rate is 40 req/s, the minimum for recovery. The old curl-per-request driver delivered only about 35 req/s when asked for 50, so the driver now batches requests per tick (`curl --parallel`) and delivers the requested rate; it prints the measured rate.
- Found and fixed by review: START_FAILED reconciliation, `node_run` rows closing exactly once, a budget-reservation reaper. The final review's high and medium findings (H1-H3, M1-M7) are fixed, one commit each; see docs/ai-usage.md.

## Cut and stretch items

Status keys as in the design doc: built, built slice, design only. Plan reference is [docs/06 §6](docs/06-execution-plan.md) (stretch and cut lists).

| Item | State | Design-doc section |
|---|---|---|
| Approval node (signal + timer) | Not built; `NODE_TYPES` is `http`, `llm`, `mcp`, `condition` | design §2 |
| `REPLAY` execution mode | Not built; `mode=REPLAY` returns 501 `NOT_IMPLEMENTED`. Orchestration replay is covered by 2 `WorkflowReplayer` tests | design §10 |
| Diamond-aware parallel compensation | Design only; compensation is sequential in reverse start order | design §5 |
| Deficit round-robin dispatcher, daily quotas | Design only; prototype rejects with 429 | design §7 |
| Redis limiter and router health, per tenant x provider buckets | Design only; limiter and health are in memory per instance (Redis runs in `full` but is unused by the app) | design §6, §7, §13 |
| OTel to Jaeger | Not built; `/trace` from Postgres is the end-to-end trace | design §11 |
| Grafana dashboard | Not built; Prometheus only | design §11 |
| Temporal fairness verified at scale | Priority and fairness keys are set and persisted; scheduling behaviour is not load-verified | design §6 |
| `resource_lease` with fencing tokens (two agents, one record) | Design only | design §13 |
| Row-level security, per-tenant egress allow-lists | Design only | design §12 |
| `business_key` dedupe for Run Again; `EFFECT_KEY_CONFLICT` request-hash check | Design only | design §5 |
| Month-partitioned audit tables | Design only | design §3 |
| Definition rule rejecting non-`READ_ONLY` tools in `llm.tools` | Enforced at runtime only (`TOOL_FORBIDDEN`) | design §9 |
| Budget period rollover | Design only (a reservation reaper is built) | design §7 |

## Test coverage (JaCoCo)

Generated with `./gradlew check jacocoTestReport` on this branch: BUILD SUCCESSFUL, 474 tests (438 app, 36 mocks), 0 failures, 2 skipped (the history recorders, which run only with `AEP_RECORD_HISTORIES=true`).

| Module | Line | Branch | Instruction |
|---|---|---|---|
| `app` | 92.9 % (4411 / 4746) | 77.3 % (1775 / 2295) | 92.4 % |
| `mocks` | 95.8 % (476 / 497) | 76.6 % (118 / 154) | 96.2 % |

By `app` package (line coverage): `router` 99 %, `dryrun` 96 %, `definition` 97 % and `definition.validation` 96 %, `tenancy` 96 %, `observability` 94 %, `engine.workflow` 92 %, `sideeffect` 91 %, `common.http` 91 %, `engine.activity` 87 %, `engine.temporal` 87 %, `tools.mcp` 87 %, `engine.reconcile` 83 %. The weakest spots are the dev-only `router.web` debug controller (14 %) and `router.persistence` (61 %). ArchUnit rules enforce the layering and the determinism of `engine.workflow`.

## With more time

From design §13, in priority order:

1. `REPLAY` mode and diamond-aware parallel compensation.
2. Approval node (signal + timer).
3. DRR dispatcher with a backlog cap, and daily quotas.
4. Redis for the limiter and router health (correct across replicas), per tenant x provider buckets.
5. OTel to Jaeger and a Grafana dashboard.
6. Temporal fairness verified at 10 k keys.
7. Row-level security and per-tenant egress allow-lists.
8. `EFFECT_KEY_CONFLICT` request-hash check and crash-during-compensation tests.
9. Month-partitioned audit tables.
10. Resource leases with fencing tokens for two agents mutating one CRM record.

## Repository layout

`app/` the platform (`api`, `definition`, `engine` with `engine.workflow` deterministic code and `engine.activity`, `execution`, `nodes`, `router`, `tools`, `sideeffect`, `dryrun`, `tenancy`, `cost`, `observability`; JDBC only in `*.persistence`) · `mocks/` mock payments, CRM, leads, LLM providers, MCP and `/admin` fault hooks · `scripts/` demos · `deploy/` Temporal and Prometheus config for `full` · `compose.yml`, `Dockerfile` · `docs/`.
