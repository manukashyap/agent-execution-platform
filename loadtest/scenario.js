// k6 scenario: 3 tenants start the lead workflow (fetch -> llm classify -> forEach of 10 http) in an open
// model. The heavy tenant ramps to saturation; the two light tenants hold a steady rate so their latency
// shows the cost of sharing the platform with the heavy one.
// Env: BASE_URL, HEAVY_KEY, LIGHT1_KEY, LIGHT2_KEY, STAGE_S (seconds per ramp step, default 60),
//      HEAVY_STEPS (comma list of heavy arrivals/s, default 20,40,80,120,160), LIGHT_RATE (default 5).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://app:8000';
const STAGE_S = Number(__ENV.STAGE_S || 60);
const HEAVY_STEPS = (__ENV.HEAVY_STEPS || '20,40,80,120,160').split(',').map(Number);
const LIGHT_RATE = Number(__ENV.LIGHT_RATE || 5);
const WORKFLOW = 'lead_enrichment_lt';

const TENANTS = {
  lt_heavy: __ENV.HEAVY_KEY,
  lt_light1: __ENV.LIGHT1_KEY,
  lt_light2: __ENV.LIGHT2_KEY,
};

const warmup = { duration: '30s', target: 5 };
const heavyStages = [warmup, ...HEAVY_STEPS.map((target) => ({ duration: `${STAGE_S}s`, target })),
  { duration: '20s', target: 0 }];
const totalS = 30 + HEAVY_STEPS.length * STAGE_S + 20;

export const accepted = new Counter('executions_accepted');
export const rejected = new Counter('executions_rejected');

export const options = {
  scenarios: {
    heavy: {
      executor: 'ramping-arrival-rate', startRate: 1, timeUnit: '1s', stages: heavyStages,
      preAllocatedVUs: 200, maxVUs: 1500, exec: 'start', env: { TENANT: 'lt_heavy' },
    },
    light1: {
      executor: 'constant-arrival-rate', rate: LIGHT_RATE, timeUnit: '1s', duration: `${totalS}s`,
      preAllocatedVUs: 20, maxVUs: 200, exec: 'start', env: { TENANT: 'lt_light1' },
    },
    light2: {
      executor: 'constant-arrival-rate', rate: LIGHT_RATE, timeUnit: '1s', duration: `${totalS}s`,
      preAllocatedVUs: 20, maxVUs: 200, exec: 'start', env: { TENANT: 'lt_light2' },
    },
  },
  thresholds: {
    // Declaring a threshold makes k6 report the per-tenant sub-metric in the summary.
    'http_req_duration{tenant:lt_heavy}': ['p(95)<60000'],
    'http_req_duration{tenant:lt_light1}': ['p(95)<60000'],
    'http_req_duration{tenant:lt_light2}': ['p(95)<60000'],
    'http_reqs{tenant:lt_heavy}': ['count>=0'],
    'http_reqs{tenant:lt_light1}': ['count>=0'],
    'http_reqs{tenant:lt_light2}': ['count>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function start() {
  const tenant = __ENV.TENANT;
  const key = `${tenant}-${__VU}-${__ITER}-${Date.now()}`;
  const res = http.post(`${BASE}/v1/workflows/${WORKFLOW}/executions`,
    JSON.stringify({ mode: 'LIVE', input: { segment: 'smb' } }), {
      headers: {
        Authorization: `Bearer ${TENANTS[tenant]}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': key,
      },
      tags: { tenant },
    });
  const ok = check(res, { 'accepted (2xx)': (r) => r.status >= 200 && r.status < 300 });
  (ok ? accepted : rejected).add(1, { tenant });
}
