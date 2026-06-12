ALTER TABLE gateway_request
    ADD COLUMN reserved_input_tokens bigint NOT NULL DEFAULT 0,
    ADD COLUMN reserved_output_tokens bigint NOT NULL DEFAULT 0;
ALTER TABLE gateway_usage ADD COLUMN estimated boolean NOT NULL DEFAULT false;
ALTER TABLE gateway_quota_reservation ALTER COLUMN expires_at SET DEFAULT now()+interval '24 hours';
ALTER TABLE gateway_spend_reservation ALTER COLUMN expires_at SET DEFAULT now()+interval '24 hours';
