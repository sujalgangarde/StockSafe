-- V2: suppliers, products, batches, balances, movements (partitioned), POs, audit, imports.

CREATE TABLE suppliers (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  name        text NOT NULL,
  phone       text,
  email       text,
  created_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  UNIQUE (tenant_id, id)
);

CREATE TABLE products (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL REFERENCES tenants(id),
  shop_id           uuid NOT NULL,
  name              text NOT NULL,
  barcode           text,
  unit              text NOT NULL DEFAULT 'pcs',
  gst_rate          numeric(5,2) NOT NULL DEFAULT 0 CHECK (gst_rate BETWEEN 0 AND 28),
  reorder_level     int NOT NULL DEFAULT 0 CHECK (reorder_level >= 0),
  custom_attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
  search_vec        tsvector GENERATED ALWAYS AS
                    (to_tsvector('simple', coalesce(name,'') || ' ' || coalesce(barcode,''))) STORED,
  version           bigint NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  deleted_at        timestamptz,
  FOREIGN KEY (tenant_id, shop_id) REFERENCES shops(tenant_id, id),
  UNIQUE (tenant_id, id)
);
-- One live barcode per shop. Partial: deleted products free the barcode.
CREATE UNIQUE INDEX uq_products_barcode ON products (tenant_id, shop_id, barcode)
  WHERE deleted_at IS NULL AND barcode IS NOT NULL;
CREATE INDEX ix_products_shop_name ON products (tenant_id, shop_id, name) WHERE deleted_at IS NULL;
CREATE INDEX ix_products_attrs  ON products USING GIN (custom_attributes jsonb_path_ops);
CREATE INDEX ix_products_search ON products USING GIN (search_vec);

CREATE TABLE batches (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL REFERENCES tenants(id),
  shop_id       uuid NOT NULL,
  product_id    uuid NOT NULL,
  supplier_id   uuid,
  batch_no      text NOT NULL,
  expiry_date   date,
  cost          numeric(12,2) NOT NULL CHECK (cost >= 0),
  mrp           numeric(12,2) NOT NULL CHECK (mrp >= 0),
  qty_received  int NOT NULL CHECK (qty_received > 0),
  created_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz,
  FOREIGN KEY (tenant_id, shop_id)    REFERENCES shops(tenant_id, id),
  FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id),
  UNIQUE (tenant_id, product_id, batch_no)
);
-- Near-expiry queries read only batches that have an expiry date.
CREATE INDEX ix_batches_expiry ON batches (tenant_id, shop_id, expiry_date)
  WHERE deleted_at IS NULL AND expiry_date IS NOT NULL;
CREATE INDEX ix_batches_product ON batches (tenant_id, product_id) WHERE deleted_at IS NULL;

-- Cached balance. Always changed in the same transaction as a movement.
CREATE TABLE stock_balances (
  batch_id    uuid PRIMARY KEY REFERENCES batches(id),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  shop_id     uuid NOT NULL,
  product_id  uuid NOT NULL,
  qty         int NOT NULL CHECK (qty >= 0),
  version     bigint NOT NULL DEFAULT 0,          -- optimistic locking
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_balances_product ON stock_balances (tenant_id, product_id) WHERE qty > 0;

-- Source of truth. Append-only. Partitioned by month.
CREATE TABLE stock_movements (
  id          uuid NOT NULL,
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  shop_id     uuid NOT NULL,
  product_id  uuid NOT NULL,
  batch_id    uuid NOT NULL REFERENCES batches(id),
  type        text NOT NULL CHECK (type IN ('RECEIVE','SALE','ADJUST')),
  qty_delta   int NOT NULL CHECK (qty_delta <> 0),
  reason      text,
  ref         text,
  created_by  uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE INDEX ix_movements_product ON stock_movements (tenant_id, product_id, occurred_at DESC);
CREATE INDEX ix_movements_batch   ON stock_movements (batch_id);

CREATE TABLE purchase_orders (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  shop_id     uuid NOT NULL,
  supplier_id uuid NOT NULL,
  status      text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SENT','RECEIVED','CANCELLED')),
  notes       text,
  created_by  uuid,
  version     bigint NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, shop_id)     REFERENCES shops(tenant_id, id),
  FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers(tenant_id, id),
  UNIQUE (tenant_id, id)
);
CREATE INDEX ix_po_shop ON purchase_orders (tenant_id, shop_id, created_at DESC);

CREATE TABLE po_items (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  po_id       uuid NOT NULL REFERENCES purchase_orders(id),
  product_id  uuid NOT NULL,
  qty         int NOT NULL CHECK (qty > 0),
  unit_cost   numeric(12,2) NOT NULL CHECK (unit_cost >= 0),
  FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id)
);
CREATE INDEX ix_po_items_po ON po_items (po_id);

CREATE TABLE audit_log (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  user_id     uuid,
  action      text NOT NULL,
  entity      text NOT NULL,
  entity_id   uuid,
  details     jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_tenant_time ON audit_log (tenant_id, created_at DESC);

CREATE TABLE import_jobs (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  shop_id        uuid NOT NULL,
  kind           text NOT NULL CHECK (kind IN ('PRODUCTS','STOCK')),
  status         text NOT NULL CHECK (status IN ('QUEUED','RUNNING','DONE','FAILED')),
  file_name      text,
  payload        text NOT NULL,                 -- the CSV; lets us resume without re-upload
  total_rows     int NOT NULL DEFAULT 0,
  processed_rows int NOT NULL DEFAULT 0,        -- checkpoint: resume starts here
  success_rows   int NOT NULL DEFAULT 0,
  error_rows     int NOT NULL DEFAULT 0,
  message        text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, shop_id) REFERENCES shops(tenant_id, id)
);

CREATE TABLE import_errors (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  job_id      uuid NOT NULL REFERENCES import_jobs(id),
  row_no      int NOT NULL,
  message     text NOT NULL,
  raw         text
);
CREATE INDEX ix_import_errors_job ON import_errors (job_id, row_no);

-- RLS on every new business table.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['suppliers','products','batches','stock_balances','stock_movements',
                           'purchase_orders','po_items','audit_log','import_jobs','import_errors'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant()) '
                   'WITH CHECK (tenant_id = current_tenant())', t);
  END LOOP;
END $$;

-- Append-only: the app can add history, never edit or erase it.
REVOKE UPDATE, DELETE ON audit_log FROM stocksafe_app;
REVOKE UPDATE, DELETE ON stock_movements FROM stocksafe_app;

-- Monthly partitions. SECURITY DEFINER because the app role cannot create tables.
-- Partitions get no app privileges: the app must go through the parent, where RLS applies.
CREATE FUNCTION ensure_movement_partitions(months_ahead int) RETURNS void
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
  AS $$
DECLARE
  m timestamp;
  pname text;
BEGIN
  FOR i IN 0..months_ahead LOOP
    m := date_trunc('month', now() AT TIME ZONE 'UTC') + make_interval(months => i);
    pname := 'stock_movements_' || to_char(m, 'YYYY_MM');
    IF to_regclass(pname) IS NULL THEN
      EXECUTE format('CREATE TABLE %I PARTITION OF stock_movements FOR VALUES FROM (%L) TO (%L)',
                     pname, m AT TIME ZONE 'UTC', (m + interval '1 month') AT TIME ZONE 'UTC');
      EXECUTE format('REVOKE ALL ON %I FROM stocksafe_app', pname);
    END IF;
  END LOOP;
END $$;
REVOKE ALL ON FUNCTION ensure_movement_partitions(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ensure_movement_partitions(int) TO stocksafe_app;

-- Safety net for any date outside the monthly partitions.
CREATE TABLE stock_movements_default PARTITION OF stock_movements DEFAULT;
REVOKE ALL ON stock_movements_default FROM stocksafe_app;

SELECT ensure_movement_partitions(3);
