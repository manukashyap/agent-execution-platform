#!/usr/bin/env bash
# P5 failure walkthrough (06 §4.9, §8; design.md "failure walkthrough"): the PDF §9 timing (provider answers at
# 15 s, node timeout 10 s) in both idempotency modes, a worker kill mid-flight, a dropped connection and a
# rate limit with Retry-After. Each scenario prints OK/FAIL with the provider call counts.
#
#   native_key   http charge, NATIVE_KEY: attempt 1 gives up at 9 s, attempt 2 waits out the lease (15 s),
#                the re-call returns the stored charge             -> SUCCEEDED, 1 charge
#   none         mcp messaging.send, NONE: same timing, no second call -> NEEDS_ATTENTION, 1 send
#   worker_kill  as native_key, but the app (the worker) is killed at ~12 s and restarted
#                                                                  -> SUCCEEDED, 1 charge
#   drop         the first charge request's connection is dropped (before any charge); the lease keeps
#                protecting it, then the NATIVE_KEY re-call charges -> SUCCEEDED, 1 charge
#   rate_limit   two 429s with Retry-After: 2 s; each releases the ledger row -> SUCCEEDED, 1 charge
#
# Prereqs: docker compose --profile lite up -d (Temporal + Postgres; the mocks too, or run your own)
#          the app with the dev profile, AEP_DEV_API_KEY set, and AEP_TOOL_DEV_CREDENTIAL set (any non-empty
#          value; the mocks accept it) so the 'none' scenario can call the messaging.send tool.
# Env:     BASE_URL (app, default http://localhost:8000), MOCKS_URL (default http://localhost:8090),
#          SCENARIOS (default "native_key none drop rate_limit worker_kill"),
#          RESTART_CMD: command that restarts the app for worker_kill, run in the background, e.g.
#            RESTART_CMD='cd /path/to/repo && AEP_DEV_API_KEY=... ./gradlew :app:bootRun'
#          Without RESTART_CMD the script kills the app and waits (RESTART_WAIT_S, default 180 s) for you to
#          start it again by hand.
set -euo pipefail

BASE="${BASE_URL:-${AEP_BASE_URL:-http://localhost:8000}}"
MOCKS="${MOCKS_URL:-${AEP_MOCKS_URL:-http://localhost:8090}}"
KEY="${AEP_DEV_API_KEY:?set AEP_DEV_API_KEY to the key the app was started with}"
SCENARIOS="${SCENARIOS:-native_key none drop rate_limit worker_kill}"
RESTART_CMD="${RESTART_CMD:-}"
RESTART_WAIT_S="${RESTART_WAIT_S:-180}"
RESTART_LOG="${RESTART_LOG:-${TMPDIR:-/tmp}/aep-walkthrough-restart.log}"
RUN="$(date +%s)"
RESULTS=()

api() {
  curl -sS -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' "$@"
}

admin() {
  curl -sS -X POST "$MOCKS/admin/$1" -H 'Content-Type: application/json' -d "$2" >/dev/null
}

field() {
  sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -n 1
}

count() {
  { grep -o "\"$1\"" || true; } | wc -l | tr -d ' '
}

log() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }

# A one-node workflow: an http charge (NATIVE_KEY via the Idempotency-Key header) with the given timeout.
charge_spec() {
  local wf=$1 timeout_s=$2 attempts=$3
  cat <<JSON
{"workflow_id":"$wf","version":1,"nodes":[
  {"id":"charge","type":"http","side_effecting":true,"timeout_s":$timeout_s,"schedule_to_close_s":180,
   "retry":{"max_attempts":$attempts,"initial_interval_ms":200},
   "config":{"url":"$MOCKS/payments/charge","method":"POST",
             "body":{"customer_id":"cus_$RUN","amount_cents":4200,"currency":"USD"}},
   "compensate":{"type":"http","config":{"url":"$MOCKS/payments/refund","method":"POST",
                 "body":{"charge_id":"{{forward.body.charge_id}}"}}}}]}
JSON
}

# A one-node workflow: the messaging.send tool (pivot, idempotency NONE) through the MCP gateway.
send_spec() {
  local wf=$1
  cat <<JSON
{"workflow_id":"$wf","version":1,"nodes":[
  {"id":"notify","type":"mcp","timeout_s":10,"schedule_to_close_s":180,
   "retry":{"max_attempts":3,"initial_interval_ms":200},
   "config":{"tool":"messaging.send","args":{"to":"+15550100","body":"walkthrough $RUN"}}}]}
JSON
}

# Publishes the spec on stdin and starts it; prints the execution id.
start() {
  local wf=$1 response id
  api -X POST "$BASE/v1/workflows" -d @- >/dev/null
  response="$(api -X POST "$BASE/v1/workflows/$wf/executions" -H "Idempotency-Key: $wf" -d '{"input":{}}')"
  id="$(printf '%s' "$response" | field executionId)"
  [ -n "$id" ] || { echo "no executionId in: $response" >&2; return 1; }
  printf '%s' "$id"
}

await_terminal() {
  local id=$1 status=""
  for _ in $(seq 1 150); do
    status="$(api "$BASE/v1/executions/$id" 2>/dev/null | field status || true)"
    case "$status" in
      SUCCEEDED|FAILED|CANCELLED|TIMED_OUT|COMPENSATED|COMPENSATION_FAILED|NEEDS_ATTENTION) break ;;
    esac
    sleep 1
  done
  printf '%s' "$status"
}

show_nodes() {
  echo "   nodes: $(api "$BASE/v1/executions/$1/nodes")"
}

# verdict <scenario> <status> <expected status> <observed count> <expected count> <count label>
verdict() {
  local line="$1: status=$2 $6=$4 (expected $3, $6=$5)"
  if [ "$2" = "$3" ] && [ "$4" = "$5" ]; then
    RESULTS+=("OK   $line")
    log "OK   $line"
  else
    RESULTS+=("FAIL $line")
    log "FAIL $line"
  fi
}

charges() {
  local payments
  payments="$(curl -sS "$MOCKS/admin/calls/payments")"
  echo "   $MOCKS/admin/calls/payments: $payments" >&2
  printf '%s' "$payments" | count amount_cents
}

scenario_native_key() {
  log "== native_key: payments.charge answers at 15 s, timeout_s 10"
  curl -sS -X POST "$MOCKS/admin/reset" >/dev/null
  admin latency '{"route":"payments.charge","ms":15000}'
  local id status
  id="$(charge_spec "wt_native_$RUN" 10 3 | start "wt_native_$RUN")"
  status="$(await_terminal "$id")"
  show_nodes "$id"
  verdict native_key "$status" SUCCEEDED "$(charges)" 1 charges
}

scenario_none() {
  log "== none: messaging.send (idempotency NONE) answers at 15 s, timeout_s 10"
  curl -sS -X POST "$MOCKS/admin/reset" >/dev/null
  admin latency '{"route":"messaging.send","ms":15000}'
  local id status sends
  id="$(send_spec "wt_none_$RUN" | start "wt_none_$RUN")"
  status="$(await_terminal "$id")"
  show_nodes "$id"
  sends="$(curl -sS "$MOCKS/admin/calls/messaging.send" | sed -n 's/.*:\([0-9]*\).*/\1/p')"
  echo "   $MOCKS/admin/calls/messaging.send: $sends"
  verdict none "$status" NEEDS_ATTENTION "$sends" 1 sends
}

scenario_drop() {
  log "== drop: the first charge connection is dropped before any charge, timeout_s 3"
  curl -sS -X POST "$MOCKS/admin/reset" >/dev/null
  admin drop-connection '{"route":"payments.charge","count":1}'
  local id status
  id="$(charge_spec "wt_drop_$RUN" 3 4 | start "wt_drop_$RUN")"
  status="$(await_terminal "$id")"
  show_nodes "$id"
  verdict drop "$status" SUCCEEDED "$(charges)" 1 charges
}

scenario_rate_limit() {
  log "== rate_limit: two 429s with Retry-After: 2 s"
  curl -sS -X POST "$MOCKS/admin/reset" >/dev/null
  admin rate-limit '{"route":"payments.charge","count":2,"retryAfterS":2}'
  local id status
  id="$(charge_spec "wt_ratelimit_$RUN" 5 4 | start "wt_ratelimit_$RUN")"
  status="$(await_terminal "$id")"
  show_nodes "$id"
  verdict rate_limit "$status" SUCCEEDED "$(charges)" 1 charges
}

app_port() {
  printf '%s' "$BASE" | sed -n 's#.*://[^:/]*:\([0-9]*\).*#\1#p'
}

wait_for_app() {
  for _ in $(seq 1 "$RESTART_WAIT_S"); do
    if curl -fsS "$BASE/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

restart_app() {
  if [ -n "$RESTART_CMD" ]; then
    log "   restarting: $RESTART_CMD (log: $RESTART_LOG)"
    nohup bash -c "$RESTART_CMD" >"$RESTART_LOG" 2>&1 &
  else
    log "   MANUAL STEP: start the app again now (same env, same port); waiting up to ${RESTART_WAIT_S}s"
  fi
  wait_for_app || { log "   the app did not come back"; return 1; }
  log "   app is back"
}

scenario_worker_kill() {
  log "== worker_kill: payments.charge answers at 15 s, timeout_s 10, the worker is killed at ~12 s"
  local port pid id status
  port="$(app_port)"
  [ -n "$port" ] || { RESULTS+=("FAIL worker_kill: BASE_URL needs an explicit port"); return; }
  curl -sS -X POST "$MOCKS/admin/reset" >/dev/null
  admin latency '{"route":"payments.charge","ms":15000}'
  id="$(charge_spec "wt_kill_$RUN" 10 4 | start "wt_kill_$RUN")"
  sleep 12
  pid="$(lsof -ti "tcp:$port" -sTCP:LISTEN | head -n 1 || true)"
  [ -n "$pid" ] || { RESULTS+=("FAIL worker_kill: nothing listens on :$port"); return; }
  log "   kill -9 $pid (the app on :$port, which is also the worker)"
  kill -9 "$pid"
  restart_app || { RESULTS+=("FAIL worker_kill: app not restarted"); return; }
  status="$(await_terminal "$id")"
  show_nodes "$id"
  verdict worker_kill "$status" SUCCEEDED "$(charges)" 1 charges
}

main() {
  curl -fsS "$MOCKS/admin/calls" >/dev/null || { echo "mocks not reachable at $MOCKS" >&2; exit 1; }
  curl -fsS "$BASE/actuator/health" >/dev/null || { echo "app not reachable at $BASE" >&2; exit 1; }
  for scenario in $SCENARIOS; do
    "scenario_$scenario"
  done
  curl -sS -X POST "$MOCKS/admin/reset" >/dev/null
  echo
  echo "== summary"
  printf '%s\n' "${RESULTS[@]}"
  for line in "${RESULTS[@]}"; do
    case "$line" in FAIL*) exit 1 ;; esac
  done
}

main "$@"
