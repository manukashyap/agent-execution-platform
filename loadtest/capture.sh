#!/usr/bin/env bash
# Runs the k6 scenario (Docker image) against the running `aeplt` stack while capturing, into
# loadtest/out/<timestamp>/ :
#   k6-summary.json / k6.log      k6 end-of-test summary (throughput, p50/p95/p99 of POST /executions)
#   docker-stats.csv              per-container CPU % and memory, sampled every SAMPLE_S seconds
#   pg-*-before/after.txt         pg_stat_database + pg_stat_wal + per-table tuple counters, both DBs
#   pg-*-delta.txt                after - before for the same
#   pgss-<db>.txt                 top statements by total time (pg_stat_statements, reset at start)
#   prom-*.json                   Prometheus range queries: queue_depth, schedule-to-start, rates
#   e2e.txt                       execution completion latency/throughput from workflow_execution
# Prereq: run.sh has brought the stack up and seeded it. Env: see scenario.js, plus SAMPLE_S (default 5).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
export COMPOSE_PROJECT_NAME=aeplt
DC=(docker compose -f "$HERE/../compose.yml" -f "$HERE/compose.override.yml" ${EXTRA_COMPOSE:+-f "$EXTRA_COMPOSE"} --profile full --profile app)
PROM="${PROM_URL:-http://localhost:9090}"
SAMPLE_S="${SAMPLE_S:-5}"
OUT="$HERE/out/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
# shellcheck disable=SC1091
. "$HERE/out/keys.env"

psqlq() { local db="$1"; shift; "${DC[@]}" exec -T postgres psql -U aep -d "$db" -X -A -F ' | ' "$@"; }

# pg_stat_database (+ WAL) as name|value rows, and per-table tuple counters for the DB.
pg_snapshot() { # db file
  psqlq "$1" -c "
    SELECT 'xact_commit', xact_commit FROM pg_stat_database WHERE datname = current_database() UNION ALL
    SELECT 'xact_rollback', xact_rollback FROM pg_stat_database WHERE datname = current_database() UNION ALL
    SELECT 'blks_read', blks_read FROM pg_stat_database WHERE datname = current_database() UNION ALL
    SELECT 'blks_hit', blks_hit FROM pg_stat_database WHERE datname = current_database() UNION ALL
    SELECT 'tup_returned', tup_returned FROM pg_stat_database WHERE datname = current_database() UNION ALL
    SELECT 'tup_fetched', tup_fetched FROM pg_stat_database WHERE datname = current_database() UNION ALL
    SELECT 'tup_inserted', tup_inserted FROM pg_stat_database WHERE datname = current_database() UNION ALL
    SELECT 'tup_updated', tup_updated FROM pg_stat_database WHERE datname = current_database() UNION ALL
    SELECT 'tup_deleted', tup_deleted FROM pg_stat_database WHERE datname = current_database() UNION ALL
    SELECT 'deadlocks', deadlocks FROM pg_stat_database WHERE datname = current_database() UNION ALL
    SELECT 'temp_bytes', temp_bytes FROM pg_stat_database WHERE datname = current_database() UNION ALL
    SELECT 'wal_bytes (cluster)', wal_bytes::bigint FROM pg_stat_wal
    ORDER BY 1" > "$2"
  psqlq "$1" -c "
    SELECT 'table:' || relname || ':ins', n_tup_ins FROM pg_stat_user_tables UNION ALL
    SELECT 'table:' || relname || ':upd', n_tup_upd FROM pg_stat_user_tables UNION ALL
    SELECT 'table:' || relname || ':del', n_tup_del FROM pg_stat_user_tables
    ORDER BY 1" >> "$2"
}

pg_delta() { # before after
  awk -F' \\| ' 'NR==FNR { b[$1]=$2; next } ($1 in b) && $2 ~ /^[0-9]+$/ && ($2-b[$1]) != 0 { printf "%s | %d\n", $1, $2-b[$1] }' "$1" "$2"
}

echo "== reset pg_stat_statements, snapshot DB counters"
for db in aep temporal temporal_visibility; do
  psqlq "$db" -c 'SELECT pg_stat_statements_reset()' >/dev/null
  pg_snapshot "$db" "$OUT/pg-$db-before.txt"
done

echo "== start docker stats sampler (every ${SAMPLE_S}s)"
echo "ts,container,cpu_pct,mem_usage,mem_pct" > "$OUT/docker-stats.csv"
(
  while true; do
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}}' \
      | grep '^aeplt-' | sed "s/^/$(date +%s),/" >> "$OUT/docker-stats.csv" || true
    sleep "$SAMPLE_S"
  done
) &
SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null || true' EXIT

START_TS="$(date +%s)"
echo "== k6 (docker image) from $(date)"
docker run --rm --network aeplt_default -v "$HERE:/loadtest:ro" -v "$OUT:/out" \
  -e BASE_URL=http://app:8000 -e HEAVY_KEY="$HEAVY_KEY" -e LIGHT1_KEY="$LIGHT1_KEY" -e LIGHT2_KEY="$LIGHT2_KEY" \
  -e STAGE_S="${STAGE_S:-60}" -e HEAVY_STEPS="${HEAVY_STEPS:-20,40,80,120,160}" -e LIGHT_RATE="${LIGHT_RATE:-5}" \
  grafana/k6:latest run --summary-export /out/k6-summary.json /loadtest/scenario.js 2>&1 | tee "$OUT/k6.log" || true
K6_END_TS="$(date +%s)"

echo "== waiting for the backlog to drain (max ${DRAIN_MAX_S:-600}s)"
for _ in $(seq 1 $(( ${DRAIN_MAX_S:-600} / 5 ))); do
  live="$(psqlq aep -t -c "SELECT count(*) FROM workflow_execution WHERE tenant_id LIKE 'lt\_%' AND status IN ('QUEUED','RUNNING')" | head -1)"
  echo "$(date +%T) live executions: $live"
  [ "$live" = "0" ] && break
  sleep 5
done
END_TS="$(date +%s)"
echo "k6_start=$START_TS k6_end=$K6_END_TS drained=$END_TS" > "$OUT/timeline.txt"
kill $SAMPLER 2>/dev/null || true

echo "== DB counters after"
for db in aep temporal temporal_visibility; do
  pg_snapshot "$db" "$OUT/pg-$db-after.txt"
  pg_delta "$OUT/pg-$db-before.txt" "$OUT/pg-$db-after.txt" > "$OUT/pg-$db-delta.txt"
  psqlq "$db" -c "
    SELECT calls, round(total_exec_time::numeric) AS total_ms, round(mean_exec_time::numeric, 3) AS mean_ms,
           rows, left(regexp_replace(query, '\s+', ' ', 'g'), 110) AS query
    FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
    ORDER BY total_exec_time DESC LIMIT 15" > "$OUT/pgss-$db.txt"
done

echo "== Prometheus range queries"
prom() { # name query
  curl -fsS -G "$PROM/api/v1/query_range" --data-urlencode "query=$2" --data-urlencode "start=$START_TS" \
    --data-urlencode "end=$END_TS" --data-urlencode "step=${SAMPLE_S}" > "$OUT/prom-$1.json" || echo "prom $1 failed"
}
prom queue_depth 'queue_depth'
prom sched_to_start_p95 'histogram_quantile(0.95, sum by (le) (rate(schedule_to_start_seconds_bucket[30s])))'
prom sched_to_start_avg 'sum(rate(schedule_to_start_seconds_sum[30s])) / sum(rate(schedule_to_start_seconds_count[30s]))'
prom temporal_sched_to_start_p95 'histogram_quantile(0.95, sum by (le) (rate(temporal_activity_schedule_to_start_latency_seconds_bucket[30s])))'
prom hikari_active 'hikaricp_connections_active'
prom hikari_pending 'hikaricp_connections_pending'
prom hikari_acquire_max 'hikaricp_connections_acquire_seconds_max'
prom exec_rate 'sum(rate(workflow_executions_total[30s]))'

echo "== end-to-end latency and completion throughput (workflow_execution)"
psqlq aep -c "
  SELECT tenant_id, status, count(*) AS n,
         round((percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM ended_at - created_at)))::numeric, 2) AS p50_s,
         round((percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM ended_at - created_at)))::numeric, 2) AS p95_s,
         round((percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM ended_at - created_at)))::numeric, 2) AS p99_s,
         round(max(extract(epoch FROM ended_at - created_at))::numeric, 2) AS max_s
  FROM workflow_execution WHERE tenant_id LIKE 'lt\_%' AND created_at >= to_timestamp($START_TS)
  GROUP BY 1, 2 ORDER BY 1, 2" > "$OUT/e2e.txt"
psqlq aep -c "
  SELECT to_char(date_trunc('minute', ended_at), 'HH24:MI') AS minute_utc,
         count(*) FILTER (WHERE tenant_id = 'lt_heavy') AS heavy_done,
         count(*) FILTER (WHERE tenant_id <> 'lt_heavy') AS light_done,
         round(count(*) / 60.0, 1) AS completions_per_s
  FROM workflow_execution WHERE tenant_id LIKE 'lt\_%' AND created_at >= to_timestamp($START_TS) AND ended_at IS NOT NULL
  GROUP BY 1 ORDER BY 1" > "$OUT/completions-per-minute.txt"
psqlq aep -c "
  SELECT to_char(date_trunc('minute', created_at), 'HH24:MI') AS minute_utc, count(*) AS admitted,
         round(count(*) / 60.0, 1) AS admitted_per_s
  FROM workflow_execution WHERE tenant_id LIKE 'lt\_%' AND created_at >= to_timestamp($START_TS)
  GROUP BY 1 ORDER BY 1" > "$OUT/admissions-per-minute.txt"
psqlq aep -c "SELECT count(*) AS node_run_rows FROM node_run WHERE tenant_id LIKE 'lt\_%'" > "$OUT/rows.txt"
echo "capture written to $OUT"
