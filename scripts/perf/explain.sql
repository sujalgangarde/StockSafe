-- EXPLAIN ANALYZE before and after, on 1,000,000 stock movements.
-- Run (PowerShell, repo root):
--   Get-Content scripts/perf/explain.sql | docker compose exec -T postgres psql -U stocksafe -d stocksafe > explain-output.txt
-- Runs as the table owner (skips RLS) so that the plans show the raw index and partition behaviour.
-- This fills your LOCAL database. Reset afterwards:  docker compose down -v
\timing on

-- ---------- 1. Seed ----------
DO $$
DECLARE m timestamp; pname text;
BEGIN
  -- partitions for the last 12 months (the app only auto-creates current + next 3)
  FOR i IN 1..12 LOOP
    m := date_trunc('month', now() AT TIME ZONE 'UTC') - make_interval(months => i);
    pname := 'stock_movements_' || to_char(m, 'YYYY_MM');
    IF to_regclass(pname) IS NULL THEN
      EXECUTE format('CREATE TABLE %I PARTITION OF stock_movements FOR VALUES FROM (%L) TO (%L)',
                     pname, m AT TIME ZONE 'UTC', (m + interval '1 month') AT TIME ZONE 'UTC');
    END IF;
  END LOOP;
END $$;

INSERT INTO tenants(id, name) VALUES ('00000000-0000-0000-0000-0000000000aa', 'Perf Tenant') ON CONFLICT DO NOTHING;
INSERT INTO shops(id, tenant_id, name, template) VALUES ('00000000-0000-0000-0000-0000000000bb', '00000000-0000-0000-0000-0000000000aa', 'Perf Shop', 'CUSTOM') ON CONFLICT DO NOTHING;
INSERT INTO products(id, tenant_id, shop_id, name, barcode)
  SELECT md5('p' || n)::uuid, '00000000-0000-0000-0000-0000000000aa', '00000000-0000-0000-0000-0000000000bb', 'Perf ' || n, 'PF' || n
  FROM generate_series(1, 2000) n ON CONFLICT DO NOTHING;
INSERT INTO batches(id, tenant_id, shop_id, product_id, batch_no, expiry_date, cost, mrp, qty_received)
  SELECT md5('b' || n)::uuid, '00000000-0000-0000-0000-0000000000aa', '00000000-0000-0000-0000-0000000000bb',
         md5('p' || n)::uuid, 'PB' || n, current_date + (n % 400) - 30, 1, 2, 100
  FROM generate_series(1, 2000) n ON CONFLICT DO NOTHING;

INSERT INTO stock_movements(id, tenant_id, shop_id, product_id, batch_id, type, qty_delta, occurred_at)
  SELECT gen_random_uuid(), '00000000-0000-0000-0000-0000000000aa', '00000000-0000-0000-0000-0000000000bb',
         md5('p' || ((g % 2000) + 1))::uuid, md5('b' || ((g % 2000) + 1))::uuid,
         'RECEIVE', (g % 7) + 1, now() - random() * interval '360 days'
  FROM generate_series(1, 1000000) g;
ANALYZE stock_movements;
ANALYZE batches;
SELECT count(*) AS movements FROM stock_movements WHERE tenant_id = '00000000-0000-0000-0000-0000000000aa';

-- ---------- 2. Product history: BEFORE the index ----------
DROP INDEX ix_movements_product;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, type, qty_delta, occurred_at FROM stock_movements
WHERE tenant_id = '00000000-0000-0000-0000-0000000000aa' AND product_id = md5('p1234')::uuid
ORDER BY occurred_at DESC LIMIT 50;

-- ---------- 2. AFTER ----------
CREATE INDEX ix_movements_product ON stock_movements (tenant_id, product_id, occurred_at DESC);
ANALYZE stock_movements;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, type, qty_delta, occurred_at FROM stock_movements
WHERE tenant_id = '00000000-0000-0000-0000-0000000000aa' AND product_id = md5('p1234')::uuid
ORDER BY occurred_at DESC LIMIT 50;

-- ---------- 3. Partition pruning: last 30 days ----------
-- BEFORE (wrapping the column in an expression hides it from the planner: every partition is scanned)
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*), sum(qty_delta) FROM stock_movements
WHERE tenant_id = '00000000-0000-0000-0000-0000000000aa' AND occurred_at + interval '0' >= now() - interval '30 days';
-- AFTER (plain comparison: only the recent partitions are scanned)
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*), sum(qty_delta) FROM stock_movements
WHERE tenant_id = '00000000-0000-0000-0000-0000000000aa' AND occurred_at >= now() - interval '30 days';

-- ---------- 4. Near-expiry list: partial index ----------
DROP INDEX ix_batches_expiry;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, batch_no, expiry_date FROM batches
WHERE tenant_id = '00000000-0000-0000-0000-0000000000aa' AND shop_id = '00000000-0000-0000-0000-0000000000bb'
  AND deleted_at IS NULL AND expiry_date IS NOT NULL AND expiry_date <= current_date + 30 ORDER BY expiry_date;
CREATE INDEX ix_batches_expiry ON batches (tenant_id, shop_id, expiry_date) WHERE deleted_at IS NULL AND expiry_date IS NOT NULL;
ANALYZE batches;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, batch_no, expiry_date FROM batches
WHERE tenant_id = '00000000-0000-0000-0000-0000000000aa' AND shop_id = '00000000-0000-0000-0000-0000000000bb'
  AND deleted_at IS NULL AND expiry_date IS NOT NULL AND expiry_date <= current_date + 30 ORDER BY expiry_date;
