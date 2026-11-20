-- V1: plans, tenants, identity, shops, usage counters, RLS.
CREATE TABLE plans (
  code            text PRIMARY KEY,
  name            text NOT NULL,
  max_shops       int,
  max_products    int,
  forecasting     boolean NOT NULL DEFAULT false,
  whatsapp_alerts boolean NOT NULL DEFAULT false,
  price_inr_month int NOT NULL DEFAULT 0
);
INSERT INTO plans VALUES
  ('free', 'Free', 1, 200,  false, false, 0),
  ('pro',  'Pro',  5, NULL, true,  true,  499);

CREATE TABLE tenants (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name        text NOT NULL,
  plan_code   text NOT NULL DEFAULT 'free' REFERENCES plans(code),
  created_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz
);

CREATE TABLE users (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email         text NOT NULL,
  password_hash text NOT NULL,
  full_name     text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz
);
CREATE UNIQUE INDEX uq_users_email ON users (lower(email)) WHERE deleted_at IS NULL;

CREATE TABLE memberships (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  user_id     uuid NOT NULL REFERENCES users(id),
  role        text NOT NULL CHECK (role IN ('OWNER','MANAGER','STAFF')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  UNIQUE (tenant_id, user_id)
);
CREATE INDEX ix_memberships_user ON memberships (user_id) WHERE deleted_at IS NULL;

CREATE TABLE refresh_tokens (
  id          uuid PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users(id),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  token_hash  text NOT NULL UNIQUE,
  expires_at  timestamptz NOT NULL,
  revoked_at  timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE shops (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  name        text NOT NULL,
  template    text NOT NULL CHECK (template IN ('MEDICAL','GROCERY','HARDWARE','CUSTOM')),
  settings    jsonb NOT NULL DEFAULT '{}'::jsonb,
  version     bigint NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  UNIQUE (tenant_id, id)
);
CREATE INDEX ix_shops_tenant_active ON shops (tenant_id, created_at) WHERE deleted_at IS NULL;

CREATE TABLE usage_counters (
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  metric      text NOT NULL,
  value       bigint NOT NULL DEFAULT 0 CHECK (value >= 0),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, metric)
);

CREATE FUNCTION current_tenant() RETURNS uuid
  LANGUAGE sql STABLE
  AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

ALTER TABLE tenants        ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships    ENABLE ROW LEVEL SECURITY;
ALTER TABLE shops          ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_counters ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenants
  USING (id = current_tenant()) WITH CHECK (id = current_tenant());
CREATE POLICY tenant_isolation ON memberships
  USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE POLICY tenant_isolation ON shops
  USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE POLICY tenant_isolation ON usage_counters
  USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());

CREATE FUNCTION login_memberships(p_user_id uuid)
  RETURNS TABLE (tenant_id uuid, role text)
  LANGUAGE sql SECURITY DEFINER SET search_path = public
  AS $$ SELECT m.tenant_id, m.role FROM memberships m
        WHERE m.user_id = p_user_id AND m.deleted_at IS NULL
        ORDER BY m.created_at $$;
REVOKE ALL ON FUNCTION login_memberships(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION login_memberships(uuid) TO stocksafe_app;
