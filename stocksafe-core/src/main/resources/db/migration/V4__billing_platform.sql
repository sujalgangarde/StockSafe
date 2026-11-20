-- V4: subscriptions, invoices, upgrade sagas, webhook log, API keys, platform-admin helpers.

CREATE TABLE subscriptions (
  id                       uuid PRIMARY KEY,
  tenant_id                uuid NOT NULL REFERENCES tenants(id),
  plan_code                text NOT NULL REFERENCES plans(code),
  status                   text NOT NULL CHECK (status IN ('PENDING','TRIALING','ACTIVE','PAST_DUE','CANCELLED')),
  razorpay_subscription_id text UNIQUE,
  trial_used               boolean NOT NULL DEFAULT false,
  trial_ends_at            timestamptz,
  current_period_end       timestamptz,
  grace_ends_at            timestamptz,               -- set when a payment fails
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_subscriptions_tenant ON subscriptions (tenant_id, created_at DESC);
CREATE INDEX ix_subscriptions_grace ON subscriptions (grace_ends_at) WHERE status = 'PAST_DUE';

CREATE TABLE invoices (
  id                   uuid PRIMARY KEY,
  tenant_id            uuid NOT NULL REFERENCES tenants(id),
  razorpay_payment_id  text UNIQUE,
  amount_inr           numeric(12,2) NOT NULL,
  status               text NOT NULL,
  created_at           timestamptz NOT NULL DEFAULT now()
);

-- Saga state for "upgrade to Pro": payment, then plan change, then limits. Compensation on failure.
CREATE TABLE upgrade_sagas (
  id                       uuid PRIMARY KEY,
  tenant_id                uuid NOT NULL REFERENCES tenants(id),
  target_plan              text NOT NULL,
  state                    text NOT NULL CHECK (state IN
    ('STARTED','PAYMENT_CONFIRMED','PLAN_APPLIED','COMPLETED','COMPENSATING','COMPENSATED')),
  razorpay_subscription_id text,
  error                    text,
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now()
);

-- Razorpay webhooks are retried, so we remember which event ids were handled. Internal: no RLS.
CREATE TABLE webhook_events (
  id           text PRIMARY KEY,
  event        text NOT NULL,
  received_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  name        text NOT NULL,
  prefix      text NOT NULL,                  -- first characters, to recognise a key in a list
  key_hash    text NOT NULL UNIQUE,           -- SHA-256, the key itself is never stored
  created_at  timestamptz NOT NULL DEFAULT now(),
  revoked_at  timestamptz
);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['subscriptions','invoices','upgrade_sagas','api_keys'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant()) '
                   'WITH CHECK (tenant_id = current_tenant())', t);
  END LOOP;
END $$;

-- Narrow SECURITY DEFINER lookups for callers that do not know the tenant yet.
CREATE FUNCTION billing_tenant_by_subscription(p_sub text) RETURNS uuid
  LANGUAGE sql SECURITY DEFINER SET search_path = public
  AS $$ SELECT tenant_id FROM subscriptions WHERE razorpay_subscription_id = p_sub $$;

CREATE FUNCTION billing_expired_grace() RETURNS TABLE (tenant_id uuid)
  LANGUAGE sql SECURITY DEFINER SET search_path = public
  AS $$ SELECT s.tenant_id FROM subscriptions s WHERE s.status = 'PAST_DUE' AND s.grace_ends_at < now() $$;

CREATE FUNCTION api_key_tenant(p_hash text) RETURNS uuid
  LANGUAGE sql SECURITY DEFINER SET search_path = public
  AS $$ SELECT tenant_id FROM api_keys WHERE key_hash = p_hash AND revoked_at IS NULL $$;

CREATE FUNCTION admin_tenants() RETURNS TABLE (id uuid, name text, plan_code text, created_at timestamptz,
                                              shops_used bigint, products_used bigint, subscription_status text)
  LANGUAGE sql SECURITY DEFINER SET search_path = public
  AS $$
  SELECT t.id, t.name, t.plan_code, t.created_at,
    COALESCE((SELECT value FROM usage_counters u WHERE u.tenant_id = t.id AND u.metric = 'shops'), 0),
    COALESCE((SELECT value FROM usage_counters u WHERE u.tenant_id = t.id AND u.metric = 'products'), 0),
    (SELECT s.status FROM subscriptions s WHERE s.tenant_id = t.id ORDER BY s.created_at DESC LIMIT 1)
  FROM tenants t WHERE t.deleted_at IS NULL ORDER BY t.created_at DESC LIMIT 500 $$;

DO $$
DECLARE f text;
BEGIN
  FOREACH f IN ARRAY ARRAY['billing_tenant_by_subscription(text)','billing_expired_grace()',
                           'api_key_tenant(text)','admin_tenants()'] LOOP
    EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f);
    EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO stocksafe_app', f);
  END LOOP;
END $$;
