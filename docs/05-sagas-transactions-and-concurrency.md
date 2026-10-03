# 05 — Sagas, Distributed Transactions & Concurrent Writes

> Status: design exploration (2026-10-02), from a research sub-agent, reviewed. Items marked **[unverified]** are to be confirmed by the tests listed in §6.

## 1. Is a failed workflow a distributed transaction?

**Not in the ACID sense, and we must not claim "rollback."**

- **2PC/XA is out.** External APIs (payments, CRM, email, LLM providers) can't take part in it, and 2PC blocks while the coordinator is down.
- **A saga gives ACD without I.**
  - Every step commits locally.
  - A failure triggers **semantic compensation**: a refund is a *new* fact, not an erased charge.
  - Intermediate states are visible to the outside world.
- **TCC (try → confirm → cancel)** is the model for anything that can be reserved: budget reservation, inventory holds, card authorisation followed by capture.
  - The budget reservation in 01 §14 already is TCC, and is named that way in the design doc.

## 2. Step taxonomy

Based on Garcia-Molina & Salem (1987) and the compensatable / pivot / retriable model:

| Kind | Meaning | Examples |
|---|---|---|
| **Compensatable** | Has a semantic inverse | charge → refund, create record → delete, hold → release |
| **Pivot** | Point of no return | send email or message, LLM spend, wire transfer |
| **Retriable** | After the pivot; must eventually succeed | log the outcome, update an internal status |
| **Read-only** | No effect | fetch, classify, lookup |

**A safe shape is `compensatable* → pivot → retriable*`.** For example, *Charge → Send Message* is correct: the charge can be refunded if sending fails.

The validator **warns** in two cases:
- A compensatable node depends on a pivot.
- More than one pivot lies on a single path.

When a pivot can't be avoided:
- **Semantic lock**: put the record in `PENDING_CONFIRMATION` until the pivot completes.
- **Countermeasure**: send a correction follow-up after the fact.
- **Escalation**: hand it to a human.

## 3. Sagas in the generic DAG interpreter

### 3.1 Declaration

The tool registry is the authority. A node may override it only for compensatable tools.

```json
// tool_registry
{"tool_name": "payments.charge", "reversibility": "COMPENSATABLE",
 "compensation": {"tool": "payments.refund", "args": {"charge_id": "{{self.output.charge_id}}"}}}
{"tool_name": "messaging.send", "reversibility": "PIVOT"}

// DAG node (optional override + staleness bound)
{"id": "charge", "type": "mcp", "config": {"tool": "payments.charge"},
 "compensate": {"tool": "payments.refund", "args": {"charge_id": "{{self.output.charge_id}}"}, "valid_for_s": 86400}}
```

MCP tool annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`) come from an **untrusted server**. The registry can tighten what a server declares but never loosen it.

### 3.2 Execution

Use `io.temporal.workflow.Saga` (current in sdk-java 1.39.0) with **sequential** compensation and `continueWithError=true`.

```java
Saga saga = new Saga(new Saga.Options.Builder().setContinueWithError(true).build());

// on node COMPLETED (workflow thread):
if (spec.compensation() != null)
    saga.addCompensation(compActivity::compensate,
        new CompInput(execId, spec.id(), spec.compensation(), outputRef));

// on FAIL_WORKFLOW failure or CanceledFailure:
Workflow.newDetachedCancellationScope(saga::compensate).run();   // runs even after cancel
```

- **Order:**
  - A node completes only after its dependencies have completed, so LIFO over completion order is always a valid **reverse topological order**, across parallel branches too.
  - The order is recorded in history, so replay is deterministic.
- **Don't use `setParallelCompensation(true)`.**
  - It runs all compensations at once and ignores dependencies.
  - If parallel compensation is ever needed, write a custom compensator that walks reverse-topological levels. That one is described in the design doc but not built.
- **Cancellation:** a cancelled scope fails activity calls, so compensations must run in `newDetachedCancellationScope`.
- **Statuses:**
  - `workflow_execution`: `COMPENSATING → COMPENSATED | COMPENSATION_FAILED`.
  - Each compensation is a `node_run` row with `phase='COMPENSATE'`.

### 3.3 Edge cases

| Case | Handling |
|---|---|
| A compensation fails | It runs through `SideEffectGuard` with key `sha256(tenant:exec:node:compensate)`, so it is idempotent. It retries with backoff, bounded by ScheduleToClose (e.g. 24 h) plus an alert, then ends in `COMPENSATION_FAILED` (needs attention). Other compensations still run. |
| The forward effect is `UNKNOWN` in the ledger | **Never compensate blindly.** Reconcile first (LOOKUP): if COMMITTED, compensate; if it never happened, skip; if `idempotency_support=NONE`, escalate to a human. |
| Left-over `PENDING` row from a cancelled activity | Same reconcile-first path |
| A pivot already ran | Not compensatable. Record it in the audit as an uncompensated effect, set `COMPENSATION_FAILED`, and escalate (or apply the countermeasure). |
| Resume 30 min later | The saga stack is workflow state, so it replays exactly. Compensations can go stale (refund windows, edited records): past `valid_for_s`, escalate. |
| Dry-run | Compensations resolve through `ExecutorRegistry` and are mocked. The preview shows the **compensation plan** with rendered args. |
| Compensation args | Taken from node *outputs* by reference (`charge_id`) and loaded inside the activity, never inlined into history. `node_output` retention must outlast the execution. |

## 4. Multi-agent workflows

The research basis is SagaLLM (arXiv 2503.11951, PVLDB 18): sagas for multi-agent LLM planning, with persistent context, compensation per step, and *independent validators*, because LLM self-validation is unreliable.

1. **Agents propose, the platform executes.**
   - An agent's `tool_call` is a proposal.
   - The gateway validates it: schema, scope, node allow-list, budget.
   - It then checks the ledger, executes the call, and registers its compensation.
   - Agents never hold credentials and never write state directly.
2. **Plan → validate → commit for pivots.**
   - Compensatable calls may run eagerly.
   - Pivot calls proposed by an agent are *staged* (persisted, not executed) and pass a policy check, optionally an independent LLM judge.
   - If `requires_approval` is set, they also pass an approval node before being committed.
3. **Orchestration, not choreography.**
   - Every agent node is a saga step, and the interpreter is the single orchestrator.
   - Choreography (agents reacting to each other's events) would lose the global compensation order and the audit trail.

## 5. Concurrent writes to the same data

### 5.1 Platform state (Postgres)

| State | Mechanism |
|---|---|
| `node_run` | Each row has a single writer: PK `(execution_id, node_id, attempt, phase)` |
| `side_effect_ledger` | PK + `INSERT … ON CONFLICT DO NOTHING` |
| `workflow_execution.status` | **State-machine compare-and-set** with a `version` column. 0 rows means the transition lost a race; a terminal status is never overwritten. |
| Per-execution budget | Workflow state; the workflow is the only writer, so no DB lock is needed |
| **Tenant budget** (shared across executions) | Atomic conditional reservation (TCC) |
| Dispatcher queue | `FOR UPDATE SKIP LOCKED` |

```sql
-- status CAS
UPDATE workflow_execution SET status = :to, version = version + 1
 WHERE id = :id AND status = ANY(:allowedFrom);

-- tenant budget: try
UPDATE tenant_budget SET reserved_usd = reserved_usd + :est
 WHERE tenant_id = :t AND spent_usd + reserved_usd + :est <= limit_usd
RETURNING reserved_usd;            -- 0 rows → BudgetExceeded
-- confirm: spent += actual, reserved -= est     cancel: reserved -= est
```

- **Isolation level:** READ COMMITTED.
  - When an `UPDATE` has to wait on a concurrently locked row, Postgres re-evaluates the `WHERE` against the newest row version, so the conditional update is race-free. This is documented behaviour; **[unverified]** until test 9 in §6 passes.
  - Use SERIALIZABLE only for multi-row invariants, with retries on 40001/40P01.
- **Locking and transactions:**
  - Lock order is tenant row, then execution row.
  - Keep transactions short.
  - **Never make an external call inside a DB transaction.**
- **At scale:** the tenant budget row becomes a hot spot. Shard it into N sub-counters or move it to Redis (design doc only).

### 5.2 Customer data mutated through tools (two agents, one CRM record)

Side-effecting nodes declare a `resource_key`, always prefixed with the tenant, e.g. `"{{tenant}}:crm:contact:{{input.contact_id}}"`.

| Mechanism | Verdict |
|---|---|
| **Lease table + fencing token** | Recommended, but described only (stretch goal, about 1 h). Acquire with `INSERT … ON CONFLICT (resource_key) DO UPDATE … WHERE expires_at < now() RETURNING fence`. Renew on activity heartbeat. When the lease is busy, throw a retryable error so Temporal backs off. |
| Postgres advisory locks | Avoid. Transaction-scoped locks hold a transaction open across the external call; session-scoped locks leak through pooled connections. |
| Temporal mutex / entity workflow per resource (`signalWithStart`) | FIFO and fair. This is the production option: one writer per entity. Described only. |
| **ETag / If-Match on the external API** | The *real* protection, because a lease can expire mid-call (Kleppmann's fencing argument). On a 412: re-read, re-plan, retry or escalate. |

```sql
CREATE TABLE resource_lease(resource_key TEXT PRIMARY KEY, holder TEXT NOT NULL,
                            fence BIGINT NOT NULL, expires_at TIMESTAMPTZ NOT NULL);
```

### 5.3 Shared scratchpad / blackboard within one execution

- **The workflow is the only writer.**
  - Parallel activities *return* deltas, and the interpreter merges them.
  - No activity writes shared state directly.
- **Reducers:**
  - Each key declares a reducer: `append | merge_map | max | error_on_conflict`.
  - The default is `error_on_conflict`, like LangGraph's `INVALID_CONCURRENT_GRAPH_UPDATE`.
- **Merge order:**
  - Deltas are applied in **node-id order within a wave**, not completion order.
  - That makes reruns reproducible, not just replays.

### 5.4 Across executions and tenants

- Resources can't collide across tenants, because every `resource_key` is tenant-prefixed.
- Contention for provider capacity is handled by the token buckets and Temporal fairness.
- Lease polling is not FIFO, so lock waits are bounded (exceeding the bound means fail or escalate). This is exposed as `lock_wait_seconds`.

## 6. Implement vs describe

**Implement (about +3 h, absorbed by the cuts in 03):**
- DAG and registry schema: `compensate`, `reversibility`, `compensation`; the validator warning on compensatable-after-pivot.
- The saga in the interpreter: sequential, `continueWithError`, detached scope, compensation statuses.
- A compensation activity running through `SideEffectGuard`, with UNKNOWN reconcile-or-escalate.
- Status CAS with a `version` column.
- Tenant budget TCC.
- A mock `/refund` endpoint.

**Describe only:**
- Resource leases with fencing (stretch goal).
- The entity/mutex workflow.
- ETag conflict handling.
- Staged plan → validate → commit for agent pivots.
- Scratchpad reducers.
- Level-wise parallel compensation.
- Sharded budget counters.
- The SERIALIZABLE retry policy.

**Tests and demos:**
1. Chain 1→5, node 4 fails non-retryably → compensations run for 3, 2, 1 in that order, and the ledger holds three `:compensate` keys.
2. Diamond A→{B,C}→D, D fails → B and C are compensated before A.
3. Pivot `send_message` completed, then a later node fails → `COMPENSATION_FAILED`, and the audit lists the uncompensated effect.
4. Refund mock always returns 500 → bounded retries end in `COMPENSATION_FAILED`, and the other compensations still run.
5. Cancel mid-flight → compensations still execute (detached scope).
6. Crash during a compensation, then retry → exactly one refund.
7. Forward effect is UNKNOWN → no blind compensation.
8. The dry-run preview includes the compensation plan.
9. 50 concurrent reservations against a budget that covers 10 → exactly 10 succeed.
10. Concurrent status CAS → a terminal status is never overwritten.

## Sources

- Temporal Saga Javadoc (1.39.0) — https://www.javadoc.io/static/io.temporal/temporal-sdk/1.39.0/io/temporal/workflow/Saga.html · Options.Builder — https://www.javadoc.io/static/io.temporal/temporal-sdk/1.39.0/io/temporal/workflow/Saga.Options.Builder.html
- sdk-java releases — https://github.com/temporalio/sdk-java/releases
- Saga vs CancellationScope — https://community.temporal.io/t/saga-compensate-vs-workflow-cancellationscope/1297
- HelloDetachedCancellationScope — https://github.com/temporalio/samples-java/blob/main/core/src/main/java/io/temporal/samples/hello/HelloDetachedCancellationScope.java
- Mutex sample — https://pkg.go.dev/github.com/temporalio/samples-go/mutex · Signal-with-Start — https://docs.temporal.io/design-patterns/signal-with-start
- SagaLLM — https://arxiv.org/abs/2503.11951 · https://www.vldb.org/pvldb/vol18/p4874-chang.pdf
- MCP tool annotations — https://blog.modelcontextprotocol.io/posts/2026-03-16-tool-annotations/
- LangGraph concurrent update error — https://docs.langchain.com/oss/python/langgraph/errors/INVALID_CONCURRENT_GRAPH_UPDATE
- Saga pattern — https://microservices.io/patterns/data/saga.html · Garcia-Molina & Salem, "Sagas", SIGMOD 1987
- Kleppmann, "How to do distributed locking" (2016)
- Postgres isolation — https://www.postgresql.org/docs/current/transaction-iso.html

**[unverified]:**
- Whether `Saga` has an async-promise variant for DAG-level parallel compensation; we assume custom code is needed.
- The hour estimates are judgement.
