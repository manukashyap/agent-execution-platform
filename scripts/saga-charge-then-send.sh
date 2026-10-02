#!/usr/bin/env bash
# P2b saga demo: charge (side-effecting, compensated by a refund) -> send message, with the send forced to fail.
# Expect COMPENSATED and exactly one charge and one refund at the payments mock.
# Variant: VARIANT=refund-fails also fails every refund -> bounded retries -> COMPENSATION_FAILED, no refund.
# Prereqs: docker compose --profile lite up -d
#          AEP_DEV_API_KEY=<your key> ./gradlew :app:bootRun --args='--spring.profiles.active=dev'
set -euo pipefail

BASE="${AEP_BASE_URL:-http://localhost:8000}"
MOCKS="${AEP_MOCKS_URL:-http://localhost:8090}"
# URL the app resolves for workflow nodes: inside the compose app container the mocks are http://mocks:8090, not localhost.
WF_MOCKS="${AEP_WF_MOCKS_URL:-$(if docker ps --format "{{.Names}}" 2>/dev/null | grep -qx aep-app-1; then echo http://mocks:8090; else echo "$MOCKS"; fi)}"
KEY="${AEP_DEV_API_KEY:?set AEP_DEV_API_KEY to the key the app was started with}"
VARIANT="${VARIANT:-}"
RUN="$(date +%s)"
WF="saga_charge_send_$RUN"

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

curl -sS -X POST "$MOCKS/admin/reset" >/dev/null
admin fail-rate '{"route":"messaging.send","percent":100,"status":500}'
EXPECTED_STATUS="COMPENSATED"
EXPECTED_REFUNDS=1
if [ "$VARIANT" = "refund-fails" ]; then
  admin fail-rate '{"route":"payments.refund","percent":100,"status":500}'
  EXPECTED_STATUS="COMPENSATION_FAILED"
  EXPECTED_REFUNDS=0
fi

echo "== POST /v1/workflows ($WF: charge [compensate: refund] -> send)"
api -X POST "$BASE/v1/workflows" -d @- <<JSON
{"workflow_id":"$WF","version":1,"nodes":[
  {"id":"charge","type":"http","side_effecting":true,
   "retry":{"max_attempts":3,"initial_interval_ms":200},
   "config":{"url":"$WF_MOCKS/payments/charge","method":"POST",
             "body":{"customer_id":"cus_$RUN","amount_cents":4200,"currency":"usd"}},
   "compensate":{"type":"http","config":{"url":"$WF_MOCKS/payments/refund","method":"POST",
                 "body":{"charge_id":"{{forward.body.charge_id}}"}}}},
  {"id":"send","type":"http","depends_on":["charge"],
   "config":{"url":"$WF_MOCKS/messaging/send","method":"POST",
             "body":{"to":"+15550100","body":"Payment {{charge.body.charge_id}} received"}}}]}
JSON
echo

echo "== POST /v1/workflows/$WF/executions"
RESPONSE="$(api -X POST "$BASE/v1/workflows/$WF/executions" -H "Idempotency-Key: saga-$RUN" -d '{"input":{}}')"
echo "$RESPONSE"
EXEC_ID="$(printf '%s' "$RESPONSE" | field executionId)"
[ -n "$EXEC_ID" ] || { echo "no executionId in the response" >&2; exit 1; }

STATUS=""
for _ in $(seq 1 90); do
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
echo "== GET $MOCKS/admin/calls/payments"
PAYMENTS="$(curl -sS "$MOCKS/admin/calls/payments")"
echo "$PAYMENTS"

CHARGES="$(printf '%s' "$PAYMENTS" | count amount_cents)"
REFUNDS="$(printf '%s' "$PAYMENTS" | count refund_id)"
echo "charges=$CHARGES refunds=$REFUNDS status=$STATUS"
if [ "$STATUS" != "$EXPECTED_STATUS" ] || [ "$CHARGES" != "1" ] || [ "$REFUNDS" != "$EXPECTED_REFUNDS" ]; then
  echo "FAIL: expected $EXPECTED_STATUS with 1 charge and $EXPECTED_REFUNDS refund(s)" >&2
  exit 1
fi
echo "OK: execution $EXEC_ID $STATUS with 1 charge and $EXPECTED_REFUNDS refund(s)"
