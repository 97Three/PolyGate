CREATE TABLE gateway_runtime_observation (
 adapter_id text PRIMARY KEY, capabilities jsonb, inventory jsonb,
 observed_at timestamptz, checked_at timestamptz NOT NULL DEFAULT now(), safe_error text
);
