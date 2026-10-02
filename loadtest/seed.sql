-- Load-test tenants, API keys and limits. Plaintext keys come in as psql variables (never stored):
--   psql -v heavy_key=... -v light1_key=... -v light2_key=... -f seed.sql
-- Limits are raised only for these tenants so admission control and budgets do not cap the test;
-- application defaults are untouched.
INSERT INTO tenant (id, name, tier) VALUES
    ('lt_heavy', 'Load test heavy tenant', 'ENTERPRISE'),
    ('lt_light1', 'Load test light tenant 1', 'STANDARD'),
    ('lt_light2', 'Load test light tenant 2', 'STANDARD')
ON CONFLICT (id) DO NOTHING;

INSERT INTO tenant_limits (tenant_id, rate_per_sec, burst, max_concurrent, max_cost_usd, max_tokens,
                           max_node_executions, max_fanout, max_tool_calls, max_duration_s)
SELECT id, 100000, 100000, 1000000, 99999999, 9000000000000, 500, 100, 3, 3600
FROM tenant WHERE id LIKE 'lt\_%'
ON CONFLICT (tenant_id) DO UPDATE SET
    rate_per_sec = EXCLUDED.rate_per_sec, burst = EXCLUDED.burst, max_concurrent = EXCLUDED.max_concurrent,
    max_cost_usd = EXCLUDED.max_cost_usd, max_tokens = EXCLUDED.max_tokens;

INSERT INTO tenant_budget (tenant_id, limit_usd)
SELECT id, 90000000 FROM tenant WHERE id LIKE 'lt\_%'
ON CONFLICT (tenant_id) DO UPDATE SET limit_usd = EXCLUDED.limit_usd;

INSERT INTO api_key (key_hash, tenant_id, scopes) VALUES
    (encode(sha256(convert_to(:'heavy_key', 'UTF8')), 'hex'), 'lt_heavy', ARRAY['*']),
    (encode(sha256(convert_to(:'light1_key', 'UTF8')), 'hex'), 'lt_light1', ARRAY['*']),
    (encode(sha256(convert_to(:'light2_key', 'UTF8')), 'hex'), 'lt_light2', ARRAY['*'])
ON CONFLICT (key_hash) DO NOTHING;
