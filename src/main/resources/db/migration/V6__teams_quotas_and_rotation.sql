CREATE TABLE gateway_team (
    id uuid PRIMARY KEY,
    name text NOT NULL,
    slug text NOT NULL UNIQUE,
    description text,
    status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','archived','disabled')),
    daily_limit_usd numeric(24,8) NOT NULL DEFAULT 0 CHECK (daily_limit_usd>=0),
    monthly_limit_usd numeric(24,8) NOT NULL DEFAULT 0 CHECK (monthly_limit_usd>=0),
    allowed_models jsonb NOT NULL DEFAULT '[]',
    allowed_providers jsonb NOT NULL DEFAULT '[]',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE gateway_api_key
    ADD COLUMN team_id uuid REFERENCES gateway_team(id) ON DELETE SET NULL,
    ADD COLUMN allowed_providers jsonb NOT NULL DEFAULT '[]',
    ADD COLUMN policy jsonb NOT NULL DEFAULT '{}',
    ADD COLUMN rotation_source uuid,
    ADD COLUMN rotation_state text NOT NULL DEFAULT 'active' CHECK (rotation_state IN ('active','pending_rotation','superseded')),
    ADD COLUMN superseded_by uuid;
CREATE UNIQUE INDEX gateway_pending_rotation_source_idx ON gateway_api_key(rotation_source) WHERE rotation_state='pending_rotation';
CREATE TABLE gateway_quota (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES gateway_user(id) ON DELETE CASCADE,
    quota_type text NOT NULL CHECK (quota_type IN ('requests','tokens','cost')),
    quota_limit numeric(24,8) NOT NULL CHECK (quota_limit>0),
    period text NOT NULL CHECK (period IN ('daily','weekly','monthly')),
    UNIQUE(user_id,quota_type,period)
);
CREATE TABLE gateway_quota_reservation (
    request_id uuid NOT NULL,
    quota_id uuid NOT NULL REFERENCES gateway_quota(id) ON DELETE CASCADE,
    amount numeric(24,8) NOT NULL CHECK (amount>=0),
    expires_at timestamptz NOT NULL DEFAULT now()+interval '15 minutes',
    PRIMARY KEY(request_id,quota_id)
);
ALTER TABLE gateway_usage ADD COLUMN cost_estimate numeric(24,8);
ALTER TABLE gateway_request ADD COLUMN tool_requested boolean NOT NULL DEFAULT false;
