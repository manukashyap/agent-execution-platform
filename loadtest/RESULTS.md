# Load test results (P9)

All numbers below come from the runs stored under `loadtest/results/` (k6 summary, `docker stats` samples,
`pg_stat_*` deltas, Prometheus range queries, SQL over `workflow_execution`). Anything not measured is marked
**not measured**. This is a single-laptop, single-node stack, so absolute numbers are a floor for a real
deployment; the shape of the saturation and the per-execution costs are the useful part.

> **Client caveat.** These runs were captured at 0fa7dc3, before outbound HTTP moved from `java.net.http` to Apache
> HttpClient 5 (796f2f8, DNS pinning) and before the pool sizing and per-call deadline fixes. They have not been
> re-run on the current client. Temporal server metrics were not captured (`prom-temporal_sched_to_start_p95.json` is empty).

## Reproduce

```bash
./loadtest/run.sh                 # full + app profiles (project aeplt), seed, k6 ramp, capture (~10 min)
./loadtest/run.sh down            # tear down and delete volumes
STAGE_S=40 HEAVY_STEPS=10 LIGHT_RATE=2 ./loadtest/capture.sh     # low-load calibration on a running stack
EXTRA_COMPOSE=loadtest/compose.shards.yml ./loadtest/run.sh       # same ramp, 32 Temporal history shards
```

- `loadtest/compose.override.yml` turns on `pg_stat_statements`; `loadtest/seed.sql` creates tenants
  `lt_heavy`, `lt_light1`, `lt_light2`, their API keys (random per run, only SHA-256 stored) and raises their
  `tenant_limits` (rate/burst 100000, max_concurrent 1000000, cost/token caps) and `tenant_budget`. App defaults are
  unchanged.
- Workflow (`loadtest/lead-workflow.json`): `fetch_leads` (http) -> `classify` (llm via router) -> `enrich`
  (http POST to the CRM mock, `forEach` of 10 leads, `max_concurrency` 10). 12 node runs / execution.
- k6 (`grafana/k6` image, `scenario.js`), open model: heavy tenant ramps linearly 5 -> 20 -> 40 -> 80 -> 120 -> 160
  executions/s (60 s per step, 6 min total); each light tenant holds 5/s. Each iteration is one
  `POST /v1/workflows/lead_enrichment_lt/executions` with a unique `Idempotency-Key`.

## Hardware and limits

Apple M4 (10 cores), 16 GiB RAM; Docker Desktop VM: 10 CPUs, 7.65 GiB. No per-container CPU/memory limits were set,
so every container (app, mocks, Postgres, Temporal, Prometheus, UI, Redis) and the k6 container share those 10 CPUs.
k6's own CPU use is **not measured** (its container is outside the `docker stats` filter). One Postgres 16 instance
holds the app DB `aep` and Temporal's `temporal` + `temporal_visibility`. Temporal server 1.32 (single process, 4 history
shards unless stated), app 1 replica (Java 21, virtual threads, Hikari pool default 10), mocks 1 replica. LLM mock
latency 100-500 ms per provider (router defaults).

## Headline numbers (saturation run, `results/saturation/`)

| Metric | Value |
|---|---|
| Offered / admitted | 25,741 executions in 350 s (avg 73.5/s, peak minute 131/s by `created_at`); 100% HTTP 2xx |
| Admission API latency (`POST /executions`) | all tenants p50 38 ms, p95 247 ms, p99 620 ms, max 1.41 s; heavy p95 260 ms, light p95 164 ms |
| **Sustained completion throughput** | **about 22 executions/s while draining** (minutes 14:02-14:06 UTC, after admissions stopped: avg 22.4/s; best minute 26.7/s) = about 270 node runs/s. Under peak admission it was lower: 11.7-12.7/s in the two busiest admission minutes (14:00-14:01, 131 and 106 admitted/s); see `completions-per-minute.txt` |
| Backlog | `queue_depth` (`temporal_workflow` and `admission_queued`) peaked at 18,172 at t+355 s; `temporal_activity` peaked at 19,736 during the drain; 300 s drain window ended with about 12 k executions still live |
| Schedule-to-start (app histogram) | mean up to 48.9 s, p95 up to 277 s (p95 is bucket-interpolated, treat as order of magnitude) |
| End-to-end latency, heavy (completed only) | SUCCEEDED p50 80 s, p95 324 s, p99 385 s (n = 7,673) |
| End-to-end latency, light (completed only) | SUCCEEDED p50 0.36 s, p95 1.6-2.4 s, p99 8.6 s (n = 481 / 483 of 1,751 each) |
| Outcome of 25,741 | heavy 22,239: 7,673 SUCCEEDED, 4,452 FAILED, 7,826 QUEUED, 2,288 RUNNING at capture; light 1,751 each: ~482 SUCCEEDED, ~100 FAILED, ~1,165 QUEUED |
| Failure mode | the FAILED executions timed out while their activity tasks waited in the backlog. The expiring timeout is the activity **ScheduleToClose** (`NodeActivityOptions` sets StartToClose and ScheduleToClose; no ScheduleToStart is set). The capture has no `error_code` breakdown, so the split by code is **not measured** |

Low-load calibration (`results/calibration/`, 851 executions at about 9.5/s, nothing queued): e2e p50 0.25-0.26 s,
p95 0.55-0.73 s, p99 1.07-1.71 s, all 851 SUCCEEDED. That is the latency of the engine when it is not saturated.

CPU and memory (`docker stats` average over the steady saturated window, t+240..353 s; CPU in % of one core):

| Container | CPU avg (max), t+240..353 s | Memory max (whole run) |
|---|---|---|
| app | 109% (139%) | 1.33 GiB |
| postgres (app DB + both Temporal DBs) | 252% (290%) | 948 MiB |
| temporal-pg (server) | 264% (492%) | 1.14 GiB |
| mocks | 11% (22%) | 412 MiB |
| prometheus / ui / redis | 3% / 0% / 0% | 111 / 13 / 9 MiB |

Summed, the stack used about 6.4 of 10 cores plus k6 (not measured): the machine was not CPU-exhausted.

## DB load (saturation run, 675 s window from k6 start to end of drain)

| | app DB `aep` | Temporal DB | Temporal visibility DB |
|---|---|---|---|
| commits | 1.15 M (1,700/s) | 2.48 M (3,670/s) | 140 k (208/s) |
| tuples inserted / updated / deleted | 369 k / 255 k / 77 | 1.51 M / 957 k / 949 k | 26 k / 46 k / 113 |
| buffer cache hit ratio | 99.7% | 98.3% | 96.5% |
| deadlocks | 0 | 0 | 0 |

WAL for the whole cluster: 4.89 GB (7.2 MB/s). Hikari (app DB): pool max 10, `pending` peaked at 16 and
`acquire_seconds_max` at 0.81 s in short bursts; average active connections 1.4.

Top statements by total time (`pg_stat_statements`, reset at start):

- App DB: `SELECT count(*) FROM workflow_execution WHERE tenant_id=? AND status IN (QUEUED,RUNNING) AND deadline_at>?`
  (the admission soft-cap count) 25,741 calls, 52.1 s total, 2.0 ms mean and growing with the live set;
  `UPDATE tenant_budget ... GREATEST(reserved - ...)` 15,542 calls, 13.6 s, 0.88 ms mean (one hot row per tenant:
  the heavy tenant's row is updated twice per LLM call); `INSERT node_run` 147,873 calls 13.0 s; `INSERT node_output` 10.6 s.
- Temporal DB: `INSERT history_node` 362 k calls 13.0 s; `UPDATE executions` 410 k calls 9.0 s;
  `SELECT timer_tasks` 1.3 ms mean, `DELETE tasks_v2 ... ` 6.4 ms mean.
- Visibility DB: `INSERT executions_visibility` upserts 44 k + 26 k calls, 75 s total, 1.1 ms mean - the largest
  single consumer of statement time in either cluster.
- The queue-depth monitor's `SELECT count(*) FROM workflow_execution WHERE status = 'QUEUED'` costs 33 ms per call (every 10 s).

## First bottleneck

Completions stopped tracking admissions at about 25-30 executions/s admitted (13:57 UTC minute: 31/s admitted,
24/s completed); from there the Temporal workflow-task backlog grew without bound while the API kept accepting at
p95 < 300 ms. At that point CPU was Postgres 167%, Temporal 199%, app 95%: the **Temporal server plus its Postgres
persistence** saturated first, not the app (about 1 core) nor the mocks. Evidence: Temporal persistence does
about 4x the row writes of the app per execution (table below); Postgres + Temporal consume about 5 cores for 22
executions/s; the backlog sits in the workflow-task queue (`temporal_workflow` 18 k) rather than at the HTTP
mocks. Raising Temporal history shards from 4 to 32 (`results/shards32/`) lifted the best minute from 26.7 to
28.4 executions/s (steady about 10% better) and raised CPU on both Postgres (327%) and Temporal (324%), so shard
count is not the main limit. I did not profile further, so whether the remaining limit is Postgres CPU, WAL fsync
inside the Docker VM, or Temporal service-side locking is **not determined**.

Second-order findings seen in the same run:

1. **No backpressure.** With `max_concurrent` raised for the test, admission accepted 100% while the backlog reached
   18 k; 4.5 k heavy executions timed out in the queue. With default limits the soft cap would reject earlier.
2. **Noisy neighbour.** Light tenants (5/s each) shared the one task queue: 481 of 1,751 completed, 100 timed out and
   about 1,165 were still queued; light completions drop to zero from minute 14:00 once the heavy backlog dominates. Priority
   tiers and matching fairness did not keep their latency flat at this overload.
3. **Admission count query** is O(live executions of the tenant) and was the top app-DB statement.

## Writes per execution (calibration run: 851 executions, all completed)

| Store | tuples inserted | updated | deleted | Notes |
|---|---|---|---|---|
| App DB `aep` | 28.1 | 19.1 | 0 | 12 `node_run` ins + 12 upd, 12 `node_output`, 1 `workflow_execution` ins + 2 upd, LLM budget path: reservation ins+upd, execution_budget ins + 2 upd, tenant_budget 2 upd, llm_call ins |
| Temporal DB | 81.3 | 71.0 | 42.8 | `history_node` 21.7, `executions` / `current_executions` 28 upd each, `timer_tasks` 22.4 ins, `transfer_tasks` 12.6 ins, `tasks_v2` 4.0 ins, `activity_info_maps` 12 ins |
| Temporal visibility | 1.2 | 3.3 | 0 | `executions_visibility` |
| **Total** | **110.6** | **93.4** | **42.8** | about 85 commits (app) + 125 (Temporal) + 8 (visibility) per execution; about 215 KB WAL |

So the dominant cost is Temporal's own persistence (about 75% of row writes), not the platform's projection tables.
Heap tuples only (index writes are extra, not counted). The saturation run shows the same ratios (app 14 ins/10 upd per
admitted execution, since most were unfinished).

## Extrapolation to 500 executions/s (linear; not measured)

Cost per execution, dividing the saturated-window CPU (t+240..353 s) by the drain throughput (22.4/s), so mixing two windows: Postgres 0.11 core-s, Temporal 0.12 core-s, app 0.05 core-s (about 0.3 core-s total
excluding mocks). At 500/s (22x):

- CPU: about 56 Postgres cores, about 59 Temporal cores, about 24 app cores. Not reachable on one node.
- Row writes: app DB about 14 k inserts/s + 9.5 k updates/s; Temporal DB about 40 k inserts/s + 35 k updates/s + 21 k deletes/s;
  WAL about 107 MB/s; about 108 k commits/s across the three DBs.
- Node runs: 6,000/s through `node_run`/`node_output` (two inserts and an update each).
- The one-row-per-tenant `tenant_budget` update becomes a hot row for any tenant above a few hundred LLM calls/s.

Linear scaling is optimistic: the shared task queue, the single `workflow_execution` count query and the 10-connection
Hikari pool all bend before that.

## 10x plan (about 220 executions/s, the next step toward 500/s)

1. **Split Temporal from the app Postgres** (own instance with NVMe, `synchronous_commit` kept on, tuned checkpoints),
   or use Temporal Cloud / Cassandra for history. Move **visibility to Elasticsearch/OpenSearch**: it is the slowest
   hot statement here (1.1 ms mean, 75 s of DB time) and a SQL visibility store is not meant for this rate.
2. **Scale Temporal horizontally**: separate frontend/history/matching/worker services; 512 history shards (fixed at
   cluster creation; 32 gave only +10% on one node, so this needs the separate DB first); more task-queue partitions.
3. **Cut events per execution**: the `forEach` of 10 costs 10 activities (21.7 `history_node` rows per execution at low load). Run small fan-outs as one batched activity or local activity, and keep child workflow per `forEach` for
   large ones. Fewer events cuts all Temporal write columns above.
4. **Cut app-DB writes**: batch `node_run`/`node_output` inserts per ready set, merge the `node_run` start/finish
   update, and write the projection asynchronously from workflow events; replace the admission `count(*)` with a
   per-tenant counter (still soft, no lock - see R3-2); drop the `status='QUEUED'` count from the queue-depth monitor.
5. **Backpressure and isolation**: reject at admission (429 + Retry-After) when `queue_depth` or schedule-to-start
   exceeds a threshold; dedicated task queues (or reserved worker slots) per tier so a heavy tenant cannot starve the others;
   a shorter `ScheduleToStart` alone would only convert queueing into earlier failures (they surfaced after about 100 s here).
6. **Scale the app tier**: separate API and worker deployments, 3+ worker replicas, raise Hikari pool above 10
   (pending reached 16), shard `tenant_budget` per (tenant, bucket) to avoid the hot row.
7. Re-run this script after each step; the first number to move is completions/s at the knee (about 25/s here).

## Not measured / caveats

- k6 container CPU, disk I/O and fsync latency in the Docker VM; Temporal service metrics (the stack scrapes
  `temporal-pg:8000`, but only the app-side `schedule_to_start_seconds` histogram was queried).
- Single run per configuration; no repeat for variance. The 4-shard and 32-shard runs differ by about 10%, close to run-to-run noise.
- Mocks hold CRM contacts in memory (grows by 10 per started execution); mock latency is fixed.
- p95/p99 of end-to-end latency in the saturation run only include executions that completed before capture, so they understate real latency.
