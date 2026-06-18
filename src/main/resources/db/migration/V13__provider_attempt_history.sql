ALTER TABLE gateway_request ADD COLUMN retry_count integer NOT NULL DEFAULT 0,ADD COLUMN upstream_status integer;
CREATE TABLE gateway_provider_attempt (
    id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES gateway_request(id),ordinal integer NOT NULL,
    provider_id text NOT NULL,upstream_model text NOT NULL,provider_protocol text NOT NULL,credential_id text,
    state text NOT NULL CHECK(state IN ('completed','failed','cancelled')),http_status integer,safe_error_code text,
    started_at timestamptz NOT NULL,ended_at timestamptz NOT NULL DEFAULT now(),latency_ms bigint NOT NULL DEFAULT 0,
    input_tokens bigint NOT NULL DEFAULT 0,output_tokens bigint NOT NULL DEFAULT 0,cache_read_tokens bigint NOT NULL DEFAULT 0,cache_write_tokens bigint NOT NULL DEFAULT 0,
    cost_estimate numeric(24,8),actual_cost numeric(24,8),billable_cost numeric(24,8),pricing_evidence jsonb,estimated boolean NOT NULL DEFAULT false,
    UNIQUE(request_id,ordinal)
);
