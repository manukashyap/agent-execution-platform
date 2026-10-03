# 04 — Local DB Analysis: SQLite for a zero-setup experience?

> Status: decision record (2026-10-02), from a research sub-agent, reviewed. Version-specific claims are cited; items marked *unverified* should be checked in Phase 0.

## Question

Can the platform use SQLite as a local file-based DB so the DB comes up automatically with the engine, with no separate DB server?

## Decision

**Keep Postgres for the app DB. Get the lightweight experience in two ways instead:**

1. **Temporal dev server.** It uses SQLite internally, runs as a single process and serves its UI on `:8233`. We use it in the `lite` profile. This costs our code nothing, because the app only talks to Temporal over gRPC.
2. **`spring-boot-docker-compose`.** `./gradlew bootRun` auto-starts Postgres and Temporal from `compose.yml`.

The key point: **Temporal's persistence can be swapped without touching our code; the app DB can't.**

## Why not SQLite for the app DB

| Area | Finding |
|---|---|
| Driver / ORM | `org.xerial:sqlite-jdbc` is current (3.53.x, Aug 2026). The Hibernate `SQLiteDialect` is community-maintained only. |
| Migrations | Flyway supports SQLite, but we'd need separate SQLite and Postgres migration sets: `JSONB`, `TEXT[]`, `UUID`, `TIMESTAMPTZ` and partial indexes all differ. |
| Supported | `INSERT … ON CONFLICT` (3.24+) and `UPDATE … RETURNING` (3.35+, with limits) |
| JSON | JSON1 is built in. SQLite's JSONB (3.45+) is a storage format only, with no GIN index and no `@>` operator. |
| **Missing** | `FOR UPDATE SKIP LOCKED` (the dispatcher queue), row-level security (tenant defence-in-depth), partitioning, advisory locks, LISTEN/NOTIFY |
| Concurrency | WAL mode allows **one writer**, and other writers get `SQLITE_BUSY`. The fix is a write pool of size 1 with `BEGIN IMMEDIATE`, so every activity thread serialises on one connection. |
| Throughput (estimate) | About 5–20k small transactions/s on a laptop SSD. We write about 4–6 rows per node, so the 25k nodes/s design target means roughly 100k+ writes/s. That's impossible on one writer and can't scale out. |
| Multi-instance / HA | A SQLite file can't be safely shared across containers or hosts. That rules out multiple workers and contradicts the 99.95% availability claim. |
| Grading | No `pg_stat_statements` or TPS for the "database load" metric. The observed bottleneck would be "SQLite single writer", an artefact of the prototype that says nothing about the design. |

## Temporal on SQLite

- `temporal server start-dev --db-filename x.db` runs the server, SQLite persistence and UI as one process. The docs say it is **not intended for production**, and it is single-writer with 1 history shard.
- In compose it runs as the `temporalio/temporal` image with `server start-dev --ip 0.0.0.0 --db-filename /data/temporal.db`.
- `spring.temporal.test-server.enabled` switches to the in-memory `TestWorkflowEnvironment`. Use it for tests only: it has no persistence across restarts (which breaks the crash/resume demos) and no UI.
- Enabling fairness on the dev server via `--dynamic-config-value matching.enableFairness=true` is *unverified*.

## Options compared

| Option | Setup friction | Extra effort | Portability burden | Grading risk |
|---|---|---|---|---|
| (a) SQLite everywhere | Lowest | +3–4 h | Total (no SKIP LOCKED, RLS or JSONB indexing) | **High** |
| (b) Two profiles: `lite` on SQLite, `full` on Postgres | Lowest / as today | +4–6 h (two migration sets, two dispatchers, two test matrices) | High, and permanent | Medium (eats time from never-cut items) |
| **(c) Postgres + Temporal dev server + auto-start ✅** | One command | **+0.5–1 h** | None | **Low** |
| (c′) Embedded Postgres (zonky 2.2.x, PG 17) | No Docker for the DB | +1 h | None | Low (built for tests; optional fallback) |
| (d) H2 in Postgres mode | Low | +2 h | Partial (no `ON CONFLICT DO UPDATE`, no JSONB, no RLS) | Medium-high (hides Postgres-only bugs) |
| (d) DuckDB / PGlite | — | — | Analytical engine / WASM only | Poor fit |

## Plan impact (applied to [03-baseline-plan.md](./03-baseline-plan.md))

- New **run profiles**:
  - `lite` (default): Postgres, Temporal dev server, mocks and app, with an in-memory rate limiter.
  - `full`: adds Temporal on Postgres, Temporal UI, Redis and the observability stack.
- **Phase 0:** grows to 2–2.5 h to add compose profiles, `spring-boot-docker-compose` (`start-only`) and the dev-server fairness smoke check.
- **Phase 7:** `RateLimiter` becomes an interface with Redis and in-memory implementations, selected by profile.
- **Phase 9:**
  - The load test runs on `full` only, with DB stats for both the app DB and the Temporal DB.
  - An optional `lite` contrast run shows SQLite saturating first.
- **Phase 10:** the README leads with the one-command `lite` path.
- **New risks:**
  - The dev server's behaviour differs from `auto-setup`, so run the Phase 2 tests once on `full`.
  - Reviewers may not have Docker; zonky plus `brew install temporal` is an optional fallback.
- A SQLite app-DB profile goes under "with more time" in the README.

## Sources

- sqlite-jdbc releases — https://github.com/xerial/sqlite-jdbc/releases
- Hibernate SQLite dialect — https://discourse.hibernate.org/t/how-to-integrate-sqlite-with-spring-boot-having-hiberate-6/7538/2 · https://www.baeldung.com/spring-boot-sqlite
- Flyway SQLite — https://github.com/flyway/flyway/blob/main/documentation/Reference/Database%20Driver%20Reference/SQLite.md
- SQLite RETURNING — https://www.sqlite.org/lang_returning.html
- Temporal CLI server — https://docs.temporal.io/cli/server
- Temporal persistence — https://docs.temporal.io/temporal-service/persistence
- Temporal SQLite in production (forum) — https://community.temporal.io/t/what-are-the-shortcomings-of-running-temporal-with-sqlite-in-production-for-small-scale-use-cases/18257
- temporalio/temporal image — https://hub.docker.com/r/temporalio/temporal · https://community.temporal.io/t/simple-docker-command-to-run-temporal-server-start-dev/9341
- Temporal Spring Boot integration — https://docs.temporal.io/develop/java/integrations/spring-boot-integration
- Spring Boot docker-compose support — https://docs.spring.io/spring-boot/reference/features/dev-services.html
- zonky embedded-postgres — https://github.com/zonkyio/embedded-postgres
- H2 ON CONFLICT — https://github.com/h2database/h2database/issues/2007

## Unverified

- The SQLite throughput figures (not benchmarked on our workload)
- The fairness flag on the dev server
- Whether the Java SDK has a dev-server launcher
- H2 support for `SKIP LOCKED`
- Fairness in the in-memory Java test server
