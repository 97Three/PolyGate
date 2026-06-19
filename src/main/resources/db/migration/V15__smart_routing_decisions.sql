CREATE TABLE gateway_routing_decision (
 id uuid PRIMARY KEY, requested_model text NOT NULL, group_id text NOT NULL,
 mode text NOT NULL, profile text NOT NULL, selected text NOT NULL, recommended text NOT NULL,
 policy_version text NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX gateway_routing_decision_time_idx ON gateway_routing_decision(occurred_at);
