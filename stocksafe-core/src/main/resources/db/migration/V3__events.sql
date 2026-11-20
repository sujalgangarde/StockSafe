-- V3: outbox, idempotency, scheduler locks, live-event log.

-- Internal tables: not exposed by any API, so no RLS (the relay reads across tenants).
CREATE TABLE outbox_events (
  seq            bigserial UNIQUE,                 -- global order, lets consumers ignore stale events
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  type           text NOT NULL,
  payload        jsonb NOT NULL,
  correlation_id text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  published_at   timestamptz
);
CREATE INDEX ix_outbox_unpublished ON outbox_events (seq) WHERE published_at IS NULL;

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

-- Live events per shop. Lets a reconnecting browser catch up with Last-Event-ID.
CREATE TABLE sse_events (
  id          bigserial PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  shop_id     uuid NOT NULL,
  type        text NOT NULL,
  data        jsonb NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_sse_shop ON sse_events (tenant_id, shop_id, id);
ALTER TABLE sse_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sse_events
  USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());

-- Cross-tenant housekeeping needs the owner's rights, so it lives in a narrow function.
CREATE FUNCTION cleanup_sse_events() RETURNS void
  LANGUAGE sql SECURITY DEFINER SET search_path = public
  AS $$ DELETE FROM sse_events WHERE created_at < now() - interval '1 day' $$;
REVOKE ALL ON FUNCTION cleanup_sse_events() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cleanup_sse_events() TO stocksafe_app;
