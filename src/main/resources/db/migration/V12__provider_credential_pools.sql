CREATE TABLE gateway_provider_credential (
    id text PRIMARY KEY,provider_id text NOT NULL REFERENCES gateway_provider(id) ON DELETE CASCADE,
    name text NOT NULL,credential_env text NOT NULL,base_url text,enabled boolean NOT NULL DEFAULT true,
    active boolean NOT NULL DEFAULT false,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX gateway_provider_active_credential_idx ON gateway_provider_credential(provider_id) WHERE active=true;
CREATE TABLE gateway_provider_credential_cursor(provider_id text PRIMARY KEY REFERENCES gateway_provider(id) ON DELETE CASCADE,sequence bigint NOT NULL DEFAULT 0);
ALTER TABLE gateway_request ADD COLUMN credential_id text;
