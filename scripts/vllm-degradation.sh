#!/usr/bin/env bash
# vLLM degradation walk-through (01 §5, 06 §4.7, task T3.5).
#
# Drives steady traffic through the router's dev-only endpoint, makes the vLLM mock slow (3 s), shows
# the router marking vLLM DEGRADED and moving traffic to llm-b (with a 5 % probe share kept on vLLM),
# then restores the latency and shows the recovery via those probes.
#
# Prerequisites: the mocks on :8090 and the app on :8000 with the `dev` profile
# (`./gradlew :app:bootRun`, which defaults to dev). Needs curl; jq is used for compact output if present.
# Env: APP or BASE_URL (app), MOCKS or MOCKS_URL (mocks), RATE, BASELINE_S, SLOW_S, RECOVER_S, POLL_S.
#
# Why RATE defaults to 50 req/s: a window counts only with >= 20 samples. While DEGRADED, vLLM gets
# every 20th request, so recovery needs 20 probes per 10 s window: 20 / (0.05 * 10 s) = 40 req/s.
# At 5 req/s DEGRADED is still reached (vLLM takes all traffic while HEALTHY: 50 samples/window), but
# recovery would never qualify. Use RATE=5 to see only the degradation half.
#
# Timeline (defaults): ~15 s baseline, ~60 s with vLLM at 3 s (DEGRADED after 2 slow windows, ~20-30 s),
# ~60 s back at 100 ms (HEALTHY after 3 fast probe windows, ~30-40 s).
set -euo pipefail

APP="${APP:-${BASE_URL:-http://localhost:8000}}"
MOCKS="${MOCKS:-${MOCKS_URL:-http://localhost:8090}}"
RATE="${RATE:-50}"
BASELINE_S="${BASELINE_S:-15}"
SLOW_S="${SLOW_S:-60}"
RECOVER_S="${RECOVER_S:-60}"
POLL_S="${POLL_S:-5}"
SLOW_MS="${SLOW_MS:-3000}"
DEFAULT_VLLM_MS="${DEFAULT_VLLM_MS:-100}"

DRIVER_PID=""

log() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }

set_vllm_latency() {
  curl -fsS -X POST "$MOCKS/admin/latency" -H 'Content-Type: application/json' \
    -d "{\"route\":\"vllm\",\"ms\":$1}" >/dev/null
  log "mock vllm latency -> $1 ms"
}

cleanup() {
  if [[ -n "$DRIVER_PID" ]]; then
    kill "$DRIVER_PID" 2>/dev/null || true
    wait "$DRIVER_PID" 2>/dev/null || true
  fi
  set_vllm_latency "$DEFAULT_VLLM_MS" || true
}
trap cleanup EXIT INT TERM

# One request per 1/RATE s, each in the background so slow (3 s) probe calls don't throttle the rate.
drive() {
  local gap
  gap=$(awk -v r="$RATE" 'BEGIN { printf "%.4f", 1 / r }')
  while true; do
    curl -s -o /dev/null -m 20 -X POST "$APP/internal/router/complete" \
      -H 'Content-Type: application/json' -d '{"prompt":"degradation demo","priority":"NORMAL"}' &
    sleep "$gap"
  done
}

print_state() {
  local state
  state=$(curl -fsS "$APP/internal/router/state") || { log "state endpoint unavailable"; return; }
  if command -v jq >/dev/null 2>&1; then
    log "$(jq -c '[.providers[] | {p: .provider, s: .state, p95: .p95Ms, n: .windowSamples}]' <<<"$state")"
    log "  decisions (last 10 s): $(jq -c '.decisionsLast10s' <<<"$state")"
  else
    log "$state"
  fi
}

watch_for() {
  local seconds=$1 elapsed=0
  while (( elapsed < seconds )); do
    sleep "$POLL_S"
    elapsed=$(( elapsed + POLL_S ))
    print_state
  done
}

preflight() {
  curl -fsS "$MOCKS/admin/latency" >/dev/null || { echo "mocks not reachable at $MOCKS" >&2; exit 1; }
  curl -fsS "$APP/internal/router/state" >/dev/null \
    || { echo "router state not reachable at $APP (is the app running with the dev profile?)" >&2; exit 1; }
}

main() {
  preflight
  set_vllm_latency "$DEFAULT_VLLM_MS"
  log "driving $RATE req/s at $APP/internal/router/complete"
  drive &
  DRIVER_PID=$!

  log "== baseline: expect vllm HEALTHY with best_score"
  watch_for "$BASELINE_S"

  set_vllm_latency "$SLOW_MS"
  log "== vllm slow: expect DEGRADED after 2 qualifying windows, then llm-b best_score + vllm probe"
  watch_for "$SLOW_S"

  set_vllm_latency "$DEFAULT_VLLM_MS"
  log "== vllm restored: expect HEALTHY after 3 fast probe windows, then vllm best_score again"
  watch_for "$RECOVER_S"
  log "done"
}

main "$@"
