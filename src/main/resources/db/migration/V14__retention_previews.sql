CREATE TABLE gateway_retention_preview (
 token_hash text PRIMARY KEY, actor_id uuid NOT NULL, document jsonb NOT NULL,
 expires_at timestamptz NOT NULL, consumed_at timestamptz
);
ALTER TABLE gateway_request ADD COLUMN detail_redacted boolean NOT NULL DEFAULT false,
 ADD COLUMN identity_redacted boolean NOT NULL DEFAULT false;
ALTER TABLE gateway_provider_attempt ADD COLUMN detail_redacted boolean NOT NULL DEFAULT false;
