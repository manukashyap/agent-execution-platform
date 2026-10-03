#!/usr/bin/env bash
# Brings up the `full` + `app` compose profiles (project aeplt), seeds 3 load-test tenants, publishes the
# lead workflow for each, then runs capture.sh (which runs k6 via its Docker image).
#   ./loadtest/run.sh            up + seed + run + capture (stack left running)
#   ./loadtest/run.sh down       tear the stack down and delete its volumes
# Env: STAGE_S, HEAVY_STEPS, LIGHT_RATE (see scenario.js), EXTRA_COMPOSE (extra overlay, e.g. compose.shards.yml). Output lands in loadtest/out/<timestamp>/.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
export COMPOSE_PROJECT_NAME=aeplt
DC=(docker compose -f "$HERE/../compose.yml" -f "$HERE/compose.override.yml" ${EXTRA_COMPOSE:+-f "$EXTRA_COMPOSE"} --profile full --profile app)
BASE="${BASE_URL:-http://localhost:8000}"
KEYS="$HERE/out/keys.env"

if [ "${1:-}" = "down" ]; then
  "${DC[@]}" down -v --remove-orphans
  exit 0
fi

mkdir -p "$HERE/out"
if [ ! -f "$KEYS" ]; then
  umask 077
  for n in HEAVY LIGHT1 LIGHT2; do echo "${n}_KEY=lt-$(openssl rand -hex 16)"; done > "$KEYS"
fi
# shellcheck disable=SC1090
. "$KEYS"

"${DC[@]}" up -d --build
echo "waiting for the app to be healthy"
for _ in $(seq 1 120); do
  curl -fsS "$BASE/actuator/health" >/dev/null 2>&1 && break
  sleep 3
done
curl -fsS "$BASE/actuator/health" >/dev/null

# Flyway has run once the app is healthy, so the tables exist.
"${DC[@]}" exec -T postgres psql -U aep -d aep -q -v ON_ERROR_STOP=1 \
  -v heavy_key="$HEAVY_KEY" -v light1_key="$LIGHT1_KEY" -v light2_key="$LIGHT2_KEY" < "$HERE/seed.sql"
for db in aep temporal temporal_visibility; do
  "${DC[@]}" exec -T postgres psql -U aep -d "$db" -q -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements'
done

for key in "$HEAVY_KEY" "$LIGHT1_KEY" "$LIGHT2_KEY"; do
  curl -fsS -X POST "$BASE/v1/workflows" -H "Authorization: Bearer $key" -H 'Content-Type: application/json' \
    --data-binary "@$HERE/lead-workflow.json" >/dev/null || echo "publish skipped (already published?)"
done
if [ "${PREPARE_ONLY:-}" = "1" ]; then
  echo "stack ready (PREPARE_ONLY)"
  exit 0
fi
echo "stack ready; running capture"
"$HERE/capture.sh"
