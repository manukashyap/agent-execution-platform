#!/usr/bin/env bash
# P1 happy path: publish the PDF §4 example unchanged, then start an execution and read it back.
# Prereqs: docker compose --profile lite up -d
#          AEP_DEV_API_KEY=<your key> ./gradlew :app:bootRun --args='--spring.profiles.active=dev'
set -euo pipefail

BASE="${AEP_BASE_URL:-http://localhost:8000}"
KEY="${AEP_DEV_API_KEY:?set AEP_DEV_API_KEY to the key the app was started with}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SPEC="$ROOT/app/src/test/resources/fixtures/pdf-example.json"

echo "== POST /v1/workflows"
curl -sS -X POST "$BASE/v1/workflows" \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  --data-binary "@$SPEC"
echo

echo "== POST /v1/workflows/lead_enrichment/executions"
RESPONSE="$(curl -sS -X POST "$BASE/v1/workflows/lead_enrichment/executions" \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: happy-$(date +%s)" \
  -d '{"mode":"LIVE","input":{"segment":"smb"}}')"
echo "$RESPONSE"

EXEC_ID="$(printf '%s' "$RESPONSE" | sed -n 's/.*"executionId":"\([^"]*\)".*/\1/p')"
if [ -n "$EXEC_ID" ]; then
  echo "== GET /v1/executions/$EXEC_ID"
  curl -sS "$BASE/v1/executions/$EXEC_ID" -H "Authorization: Bearer $KEY"
  echo
fi
