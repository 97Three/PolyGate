CREATE TABLE gateway_oidc_flow (
 state_hash text PRIMARY KEY, browser_hash text NOT NULL, nonce text NOT NULL,
 verifier text NOT NULL, return_path text NOT NULL,expires_at timestamptz NOT NULL
);
CREATE TABLE gateway_federated_identity (
 issuer text NOT NULL,subject text NOT NULL,user_id uuid NOT NULL REFERENCES gateway_user(id) ON DELETE CASCADE,
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(issuer,subject)
);
