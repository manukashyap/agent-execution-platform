# mocks

Deterministic mock external services for the platform (06 §4.14). One Spring Boot app, port 8090. No randomness: injected failures are counter-based.

## Route table

| Method + path | Route key | Notes |
|---|---|---|
| `POST /llm/{llm-a\|llm-b\|vllm}/v1/chat/completions` | `llm-a` `llm-b` `vllm` | OpenAI-shaped. Default latency 200 / 500 / 100 ms |
| `POST /mcp` | `mcp` | JSON-RPC 2.0: `tools/list`, `tools/call` |
| `POST /payments/charge` | `payments.charge` | `Idempotency-Key` required (400 if missing) |
| `POST /payments/refund` | `payments.refund` | `{charge_id}`, same idempotency semantics |
| `GET /payments/charges/{charge_id}` | `payments.lookup` | 404 if unknown |
| `GET /payments/charges?idempotency_key=` | `payments.lookup` | `{charges:[...]}` |
| `POST /crm/contacts` | `crm.upsert` | always creates; `{contact_id}` |
| `GET /crm/contacts?external_ref=` | `crm.get` | `{contacts:[...]}` |
| `DELETE /crm/contacts/{id}` | `crm.delete` | 200 `{deleted:true}`, 404 if unknown (compensation) |
| `GET /leads?limit=N` | `leads.fetch` | default 5, clamped to 1..200 |
| `POST /messaging/send` | `messaging.send` | `{message_id}`, no idempotency |
| `/admin/*` | | see below |

Errors are `{"error": "<code>"}` with the HTTP status.

## LLM behaviour

Request `{model, messages:[{role, content, tool_call_id?}], tools?}`.

- `tools` non-empty and no `role=tool` message yet: one `tool_calls` entry for `tools[0]`; `arguments` is the last user message content if it is a JSON object, else `"{}"`; `finish_reason=tool_calls`, `content=null`.
- Otherwise, if a `role=tool` message exists: `content = "final: " + <last tool content>`.
- Otherwise: `content = {"label":"hot|warm|cold"}` chosen by hash of all message contents.
- `usage` tokens = `ceil(chars/4)` (prompt: all message contents; completion: content or tool arguments).

## MCP

Tools: `payments.charge`, `payments.refund`, `messaging.send`, `crm.upsert`, `crm.get`, `crm.delete`, `leads.fetch` (input schemas mirror the REST bodies).

- `crm.delete {external_ref}` (the compensation of `crm.upsert`) deletes every contact with that ref and returns `{external_ref, deleted, deleted_count}`; repeating it returns `deleted:false`, not an error, so a retried compensation is safe. Hits count on `crm.delete`.

- Success: `result: {content:[{type:"json", json:<service response>}], isError:false}`.
- Tool failure (injected failure, missing idempotency key, in-flight 409, unknown charge): `isError:true`, `json: {error, status}`.
- JSON-RPC errors: `-32601` unknown method or tool, `-32602` invalid params (missing/ill-typed arguments), `-32600` invalid request.
- The `Idempotency-Key` HTTP header of the `/mcp` request is forwarded to payments logic.
- Rate limits (429 + `Retry-After`) and dropped connections on `mcp` or on a tool route surface at HTTP level, not as `isError`.
- Hits are counted on `mcp` and on the underlying tool route (e.g. `payments.charge`).

## Payments semantics

- Processing runs on its own thread: with `payments.charge` latency 15 s, the charge is executed server-side at 15 s even if the client timed out or disconnected.
- Same key while the first call is in flight: `409 {"error":"in_progress"}`. Same key after completion: the stored response, 200, no new charge. Keys are scoped per operation (charge vs refund).
- Latency applies to processing (after validation); injected failures, rate limits and drops happen before any charge is created.

## Admin

| Endpoint | Body | Effect |
|---|---|---|
| `POST /admin/latency` | `{route, ms}` | override latency (`GET` shows effective values incl. LLM defaults) |
| `POST /admin/fail-rate` | `{route, percent, status?=500}` | counter-based: call n fails iff `floor(n*p/100) > floor((n-1)*p/100)`, i.e. every `100/p`-th call; counter restarts when reconfigured; 100 fails all |
| `POST /admin/rate-limit` | `{route, count, retryAfterS}` | next `count` calls return 429 + `Retry-After` |
| `POST /admin/drop-connection` | `{route, count}` | next `count` calls close the socket without any response |
| `GET /admin/calls` | | hit counts per route key |
| `GET /admin/calls/payments` | | `{charges:[...], refunds:[...]}` actually executed (assert "exactly one charge") |
| `GET /admin/calls/{route}` | | `{route: count}` |
| `POST /admin/reset` | | clears knobs, counters, charges, contacts, id sequences |

Order of effects per call: count, drop, rate-limit, latency, fail-rate.

Note: the JDK `HttpClient` silently retries idempotent requests (GET) once on a dropped connection, consuming two drops; use POST routes when testing drops through it.
