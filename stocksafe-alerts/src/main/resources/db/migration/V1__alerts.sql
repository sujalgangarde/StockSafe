-- Alerts service tables. They live in the same database but are written ONLY by this service.
-- It learns about stock only from events, never by reading core tables.

CREATE OR REPLACE FUNCTION current_tenant() RETURNS uuid
  LANGUAGE sql STABLE
  AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

CREATE TABLE IF NOT EXISTS processed_events (
  consumer      text NOT NULL,
  event_id      uuid NOT NULL,
  processed_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);
CREATE TABLE IF NOT EXISTS shedlock (
  name        varchar(64) PRIMARY KEY,
  lock_until  timestamp(3) NOT NULL,
  locked_at   timestamp(3) NOT NULL,
  locked_by   varchar(255) NOT NULL
);

-- Internal (no RLS): plan and contact per tenant, learned from events.
CREATE TABLE tenant_plans (
  tenant_id   uuid PRIMARY KEY,
  plan_code   text NOT NULL DEFAULT 'free',
  owner_email text,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE alert_rules (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  shop_id        uuid,                       -- NULL = all shops
  type           text NOT NULL CHECK (type IN ('NEAR_EXPIRY','LOW_STOCK')),
  threshold_days int CHECK (threshold_days BETWEEN 1 AND 365),
  enabled        boolean NOT NULL DEFAULT true,
  created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE product_snapshots (
  product_id    uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  shop_id       uuid NOT NULL,
  name          text NOT NULL,
  reorder_level int NOT NULL DEFAULT 0,
  last_seq      bigint NOT NULL DEFAULT 0    -- ignore older events that arrive late
);

CREATE TABLE batch_snapshots (
  batch_id      uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  shop_id       uuid NOT NULL,
  product_id    uuid NOT NULL,
  product_name  text NOT NULL,
  batch_no      text NOT NULL,
  expiry_date   date,
  qty           int NOT NULL,
  last_seq      bigint NOT NULL DEFAULT 0,
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_batch_snap_expiry  ON batch_snapshots (tenant_id, expiry_date) WHERE qty > 0 AND expiry_date IS NOT NULL;
CREATE INDEX ix_batch_snap_product ON batch_snapshots (tenant_id, product_id);

CREATE TABLE sales_daily (
  tenant_id   uuid NOT NULL,
  shop_id     uuid NOT NULL,
  product_id  uuid NOT NULL,
  day         date NOT NULL,
  qty         int NOT NULL,
  PRIMARY KEY (tenant_id, product_id, day)
);

CREATE TABLE alerts (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  shop_id     uuid NOT NULL,
  type        text NOT NULL,
  product_id  uuid,
  batch_id    uuid,
  message     text NOT NULL,
  dedupe_key  text NOT NULL,
  status      text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  resolved_at timestamptz
);
-- One OPEN alert per subject. After it is resolved, the same problem can alert again.
CREATE UNIQUE INDEX uq_alerts_open ON alerts (tenant_id, dedupe_key) WHERE status = 'OPEN';
CREATE INDEX ix_alerts_shop ON alerts (tenant_id, shop_id, status, created_at DESC);

CREATE TABLE notifications (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  alert_id        uuid,
  channel         text NOT NULL CHECK (channel IN ('EMAIL','SMS','WHATSAPP')),
  recipient       text NOT NULL,
  subject         text NOT NULL,
  body            text NOT NULL,
  status          text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
  attempts        int NOT NULL DEFAULT 0,
  last_error      text,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  sent_at         timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_due ON notifications (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_notifications_tenant ON notifications (tenant_id, created_at DESC);

CREATE TABLE forecasts (
  tenant_id      uuid NOT NULL,
  shop_id        uuid NOT NULL,
  product_id     uuid NOT NULL,
  product_name   text NOT NULL,
  avg_daily      numeric(12,3) NOT NULL,
  stock          int NOT NULL,
  days_of_cover  numeric(10,1),
  suggested_qty  int NOT NULL,
  computed_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, product_id)
);

-- Alerts' own outbox (events for core: alert.created, alert.resolved). Internal, no RLS.
CREATE TABLE alert_outbox_events (
  seq            bigserial UNIQUE,
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  type           text NOT NULL,
  payload        jsonb NOT NULL,
  correlation_id text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  published_at   timestamptz
);
CREATE INDEX ix_alert_outbox_unpublished ON alert_outbox_events (seq) WHERE published_at IS NULL;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['alert_rules','product_snapshots','batch_snapshots','sales_daily',
                           'alerts','notifications','forecasts'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant()) '
                   'WITH CHECK (tenant_id = current_tenant())', t);
  END LOOP;
END $$;

-- The dispatcher serves ALL tenants, so it uses two narrow owner-rights functions.
-- notif_claim leases due notifications for 2 minutes (a crashed worker's lease simply expires).
CREATE FUNCTION notif_claim(p_limit int)
  RETURNS TABLE (id uuid, tenant_id uuid, channel text, recipient text, subject text, body text, attempts int)
  LANGUAGE sql SECURITY DEFINER SET search_path = public
  AS $$
  UPDATE notifications n
     SET attempts = n.attempts + 1, next_attempt_at = now() + interval '2 minutes'
   WHERE n.id IN (SELECT x.id FROM notifications x
                   WHERE x.status = 'PENDING' AND x.next_attempt_at <= now()
                   ORDER BY x.next_attempt_at LIMIT p_limit FOR UPDATE SKIP LOCKED)
  RETURNING n.id, n.tenant_id, n.channel, n.recipient, n.subject, n.body, n.attempts $$;

-- Retry with exponential backoff: 10s, 20s, 40s, ... After 6 attempts the notification is FAILED.
CREATE FUNCTION notif_mark(p_id uuid, p_ok boolean, p_error text) RETURNS void
  LANGUAGE sql SECURITY DEFINER SET search_path = public
  AS $$
  UPDATE notifications SET
    status = CASE WHEN p_ok THEN 'SENT' WHEN attempts >= 6 THEN 'FAILED' ELSE 'PENDING' END,
    sent_at = CASE WHEN p_ok THEN now() ELSE sent_at END,
    last_error = CASE WHEN p_ok THEN NULL ELSE left(p_error, 500) END,
    next_attempt_at = CASE WHEN p_ok OR attempts >= 6 THEN next_attempt_at
                           ELSE now() + (power(2, attempts) * interval '5 seconds') END
  WHERE id = p_id $$;

REVOKE ALL ON FUNCTION notif_claim(int) FROM PUBLIC;
REVOKE ALL ON FUNCTION notif_mark(uuid, boolean, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notif_claim(int) TO stocksafe_app;
GRANT EXECUTE ON FUNCTION notif_mark(uuid, boolean, text) TO stocksafe_app;
