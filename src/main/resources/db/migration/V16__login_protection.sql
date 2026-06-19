CREATE TABLE gateway_login_attempt (
 identity_hash text PRIMARY KEY, failures integer NOT NULL DEFAULT 0,
 locked_until timestamptz,updated_at timestamptz NOT NULL DEFAULT now()
);
