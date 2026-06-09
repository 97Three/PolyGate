CREATE TABLE gateway_provider (
    id text PRIMARY KEY,
    display_name text NOT NULL,
    protocol text NOT NULL CHECK (protocol IN ('anthropic', 'openai_compat')),
    base_url text NOT NULL,
    credential_env text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE gateway_model (
    public_id text PRIMARY KEY,
    provider_id text NOT NULL REFERENCES gateway_provider(id),
    upstream_id text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    profile jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX gateway_model_provider_idx ON gateway_model(provider_id);

CREATE TABLE model_alias (
    alias text PRIMARY KEY,
    target_model text NOT NULL REFERENCES gateway_model(public_id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE gateway_user (
    id uuid PRIMARY KEY,
    username text NOT NULL UNIQUE,
    password_hash text NOT NULL,
    role text NOT NULL CHECK (role IN ('admin', 'operator', 'viewer')),
    disabled boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE gateway_api_key (
    id uuid PRIMARY KEY,
    label text NOT NULL,
    secret_hash bytea NOT NULL UNIQUE,
    user_id uuid REFERENCES gateway_user(id),
    enabled boolean NOT NULL DEFAULT true,
    allowed_models jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz
);

CREATE TABLE gateway_session (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES gateway_user(id),
    token_hash bytea NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX gateway_session_expiry_idx ON gateway_session(expires_at);

CREATE TABLE gateway_request (
    id uuid PRIMARY KEY,
    client_protocol text NOT NULL,
    requested_model text NOT NULL,
    provider_id text,
    upstream_model text,
    state text NOT NULL CHECK (state IN ('started', 'completed', 'failed')),
    http_status integer,
    started_at timestamptz NOT NULL DEFAULT now(),
    ended_at timestamptz,
    latency_ms bigint,
    safe_error_code text
);
CREATE INDEX gateway_request_started_idx ON gateway_request(started_at DESC);

CREATE TABLE gateway_usage (
    request_id uuid PRIMARY KEY REFERENCES gateway_request(id),
    input_tokens bigint NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens bigint NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    cache_read_tokens bigint NOT NULL DEFAULT 0 CHECK (cache_read_tokens >= 0),
    cache_write_tokens bigint NOT NULL DEFAULT 0 CHECK (cache_write_tokens >= 0),
    observed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE gateway_audit (
    id uuid PRIMARY KEY,
    actor_id uuid,
    action text NOT NULL,
    resource_type text NOT NULL,
    resource_id text,
    safe_details jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX gateway_audit_time_idx ON gateway_audit(occurred_at DESC);

CREATE TABLE gateway_setting (
    name text PRIMARY KEY,
    document jsonb NOT NULL,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);
