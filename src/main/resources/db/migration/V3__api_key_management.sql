ALTER TABLE gateway_api_key RENAME COLUMN label TO name;
ALTER TABLE gateway_api_key ADD COLUMN key_prefix text NOT NULL DEFAULT '';
ALTER TABLE gateway_api_key ADD COLUMN last_used_at timestamptz;
ALTER TABLE gateway_api_key ADD COLUMN key_group text;
ALTER TABLE gateway_api_key ADD COLUMN principal_type text NOT NULL DEFAULT 'user';
ALTER TABLE gateway_api_key ADD COLUMN purpose text;
