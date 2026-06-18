ALTER TABLE gateway_request ADD COLUMN provider_protocol text NOT NULL DEFAULT 'anthropic',ADD COLUMN traffic_class text NOT NULL DEFAULT 'business',ADD COLUMN client_ip text,ADD COLUMN has_idempotency_key boolean NOT NULL DEFAULT false;
ALTER TABLE gateway_request DROP CONSTRAINT gateway_request_state_check;
ALTER TABLE gateway_request ADD CONSTRAINT gateway_request_state_check CHECK(state IN ('started','completed','failed','cancelled'));
CREATE TABLE gateway_idempotency (
    scope_hash bytea PRIMARY KEY,request_fingerprint bytea NOT NULL,request_id uuid NOT NULL UNIQUE,
    terminal boolean NOT NULL DEFAULT false,created_at timestamptz NOT NULL DEFAULT now()
);
