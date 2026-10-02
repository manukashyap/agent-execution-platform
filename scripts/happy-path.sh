#!/usr/bin/env bash
# P2a happy path: publish the PDF §4 example unchanged (validation only), then run an http -> llm -> condition
# -> http workflow against the mocks end to end and print the execution and its nodes.
# Prereqs: docker compose --profile lite up -d
#          AEP_DEV_API_KEY=<your key> ./gradlew :app:bootRun --args='--spring.profiles.active=dev'
set -euo pipefail

BASE="${AEP_BASE_URL:-http://localhost:8000}"
MOCKS="${AEP_MOCKS_URL:-http://localhost:8090}"
KEY="${AEP_DEV_API_KEY:?set AEP_DEV_API_KEY to the key the app was started with}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SPEC="$ROOT/app/src/test/resources/fixtures/pdf-example.json"
RUN="$(date +%s)"
WF="happy_path_$RUN"

api() {
  curl -sS -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' "$@"
}

field() {
  sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -n 1
}

curl -sS -X POST "$MOCKS/admin/reset" >/dev/null

echo "== POST /v1/workflows (PDF example, unchanged)"
api -X POST "$BASE/v1/workflows" --data-binary "@$SPEC"
echo

echo "== POST /v1/workflows ($WF: http -> llm -> condition -> http)"
api -X POST "$BASE/v1/workflows" -d @- <<JSON
{"workflow_id":"$WF","version":1,"nodes":[
  {"id":"fetch_leads","type":"http","config":{"url":"$MOCKS/leads?limit=3","method":"GET"}},
  {"id":"classify","type":"llm","depends_on":["fetch_leads"],
   "config":{"prompt":"Classify these leads as hot, warm or cold: {{fetch_leads.body}}"}},
  {"id":"has_leads","type":"condition","depends_on":["fetch_leads"],
   "config":{"left":"\$.fetch_leads.status","op":"eq","right":200,"then":["upsert"],"else":[]}},
  {"id":"upsert","type":"http","depends_on":["has_leads","classify"],
   "config":{"url":"$MOCKS/crm/contacts","method":"POST",
             "body":{"external_ref":"happy-$RUN","name":"{{fetch_leads.body.leads.0.name}}",
                     "email":"{{fetch_leads.body.leads.0.email}}","label":"{{classify.content}}"}}}]}
JSON
echo

echo "== POST /v1/workflows/$WF/executions"
RESPONSE="$(api -X POST "$BASE/v1/workflows/$WF/executions" -H "Idempotency-Key: happy-$RUN" \
  -d '{"mode":"LIVE","input":{"segment":"smb"}}')"
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

echo "== GET /v1/executions/$EXEC_ID"
api "$BASE/v1/executions/$EXEC_ID"
echo
echo "== GET /v1/executions/$EXEC_ID/nodes"
api "$BASE/v1/executions/$EXEC_ID/nodes"
echo

if [ "$STATUS" != "SUCCEEDED" ]; then
  echo "FAIL: execution ended $STATUS" >&2
  exit 1
fi
echo "OK: execution $EXEC_ID SUCCEEDED"
