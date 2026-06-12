ALTER TABLE gateway_request ADD COLUMN price_card jsonb, ADD COLUMN team_id uuid;
ALTER TABLE gateway_api_key ADD COLUMN quota_subject uuid;
UPDATE gateway_api_key SET quota_subject=id;
ALTER TABLE gateway_request ADD COLUMN quota_subject uuid;
CREATE INDEX gateway_request_team_time_idx ON gateway_request(team_id,started_at DESC);
CREATE TABLE gateway_spend_reservation (
    request_id uuid PRIMARY KEY,
    subject_id uuid,
    team_id uuid,
    amount numeric(24,8) NOT NULL CHECK(amount>=0),
    expires_at timestamptz NOT NULL DEFAULT now()+interval '15 minutes'
);
CREATE INDEX gateway_spend_reservation_key_idx ON gateway_spend_reservation(subject_id);
CREATE INDEX gateway_spend_reservation_team_idx ON gateway_spend_reservation(team_id);
