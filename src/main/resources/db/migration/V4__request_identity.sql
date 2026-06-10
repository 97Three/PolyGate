ALTER TABLE gateway_request
    ADD COLUMN user_id uuid REFERENCES gateway_user(id) ON DELETE SET NULL,
    ADD COLUMN api_key_id uuid REFERENCES gateway_api_key(id) ON DELETE SET NULL,
    ADD COLUMN stream boolean NOT NULL DEFAULT false;

CREATE INDEX gateway_request_user_time_idx ON gateway_request(user_id, started_at DESC);
CREATE INDEX gateway_request_key_time_idx ON gateway_request(api_key_id, started_at DESC);
