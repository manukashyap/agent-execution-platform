#!/usr/bin/env bash
# P6 dry run: the PDF §7 lead workflow (fetch -> classify -> CRM upsert -> send) in DRY_RUN mode against the mocks.
# The read-only fetch and the LLM classify run live (one mock hit each); the CRM write and the send are mocked by the
# platform (zero hits). Prints GET /v1/executions/{id}/preview and checks the mocks' call counters.
# Prereqs: docker compose --profile lite up -d
#          ./gradlew :mocks:bootRun
#          AEP_DEV_API_KEY=<your key> ./gradlew :app:bootRun --args='--spring.profiles.active=dev'
# Set MOCK_LLM=true to mock the classify call too (then the LLM count must be 0).
set -euo pipefail

BASE="${BASE_URL:-${AEP_BASE_URL:-http://localhost:8000}}"
MOCKS="${MOCKS_URL:-${AEP_MOCKS_URL:-http://localhost:8090}}"
KEY="${AEP_DEV_API_KEY:?set AEP_DEV_API_KEY to the key the app was started with}"
MOCK_LLM="${MOCK_LLM:-false}"
RUN="$(date +%s)"
WF="dry_run_leads_$RUN"

api() {
  curl -sS -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' "$@"
}

field() {
  grep -o "\"$1\":\"[^\"]*\"" | head -n 1 | cut -d'"' -f4
}

# Hit count for one mocks route from GET /admin/calls (0 when the route was never hit).
calls() {
  local count
  count="$(printf '%s' "$CALLS" | sed -n "s/.*\"$1\":\([0-9]*\).*/\1/p" | head -n 1)"
  echo "${count:-0}"
}

FAILED=0
expect() {
  if [ "$2" -ne "$3" ]; then
    echo "FAIL: $1 hit $2 times, expected $3" >&2
    FAILED=1
  else
    echo "ok: $1 hit $2 times"
  fi
}

curl -sS -X POST "$MOCKS/admin/reset" >/dev/null

echo "== POST /v1/workflows ($WF: fetch -> classify -> crm.upsert -> messaging.send)"
api -X POST "$BASE/v1/workflows" -d @- <<JSON
{"workflow_id":"$WF","version":1,"nodes":[
  {"id":"fetch_leads","type":"http","side_effecting":false,
   "config":{"url":"$MOCKS/leads?limit=3","method":"GET"}},
  {"id":"classify_leads","type":"llm",
   "config":{"prompt":"Classify each lead as hot, warm or cold: {{fetch_leads.body}}"}},
  {"id":"update_crm","type":"mcp",
   "config":{"tool":"crm.upsert","args":{"external_ref":"dry-$RUN","name":"{{fetch_leads.body.leads.0.name}}",
             "email":"{{fetch_leads.body.leads.0.email}}"}},
   "compensate":{"type":"mcp","config":{"tool":"crm.delete","args":{"external_ref":"dry-$RUN"}}}},
  {"id":"send_message","type":"mcp",
   "config":{"tool":"messaging.send","args":{"to":"{{fetch_leads.body.leads.0.email}}","body":"Hello"}}}]}
JSON
echo

echo "== POST /v1/workflows/$WF/executions (DRY_RUN, mockLlm=$MOCK_LLM)"
RESPONSE="$(api -X POST "$BASE/v1/workflows/$WF/executions" -H "Idempotency-Key: dry-$RUN" \
  -d "{\"mode\":\"DRY_RUN\",\"dryRun\":{\"mockLlm\":$MOCK_LLM},\"input\":{\"segment\":\"smb\"}}")"
echo "$RESPONSE"
EXEC_ID="$(printf '%s' "$RESPONSE" | field executionId)"
[ -n "$EXEC_ID" ] || { echo "no executionId in the response" >&2; exit 1; }

STATUS=""
for _ in $(seq 1 60); do
  STATUS="$(api "$BASE/v1/executions/$EXEC_ID" | field status)"
  case "$STATUS" in
    SUCCEEDED|FAILED|CANCELLED|TIMED_OUT|COMPENSATED|COMPENSATION_FAILED) break ;;
  esac
  sleep 1
done

echo "== GET /v1/executions/$EXEC_ID/preview"
api "$BASE/v1/executions/$EXEC_ID/preview"
echo

CALLS="$(curl -sS "$MOCKS/admin/calls")"
echo "== GET $MOCKS/admin/calls"
echo "$CALLS"
LLM=$(( $(calls llm-a) + $(calls llm-b) + $(calls vllm) ))
expect "leads.fetch" "$(calls leads.fetch)" 1
if [ "$MOCK_LLM" = "true" ]; then expect "llm" "$LLM" 0; else expect "llm" "$LLM" 1; fi
expect "mcp" "$(calls mcp)" 0
expect "crm.upsert" "$(calls crm.upsert)" 0
expect "messaging.send" "$(calls messaging.send)" 0

if [ "$STATUS" != "SUCCEEDED" ]; then
  echo "FAIL: dry run ended $STATUS" >&2
  exit 1
fi
[ "$FAILED" -eq 0 ] || exit 1
echo "OK: dry run $EXEC_ID SUCCEEDED; side effects mocked, nothing written"
