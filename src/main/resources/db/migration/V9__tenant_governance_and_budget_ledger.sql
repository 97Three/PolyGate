ALTER TABLE gateway_api_key ADD COLUMN organization_id text NOT NULL DEFAULT 'org_local',ADD COLUMN project_id text NOT NULL DEFAULT 'prj_default',ADD COLUMN environment_id text NOT NULL DEFAULT 'env_default';
ALTER TABLE gateway_request ADD COLUMN organization_id text NOT NULL DEFAULT 'org_local',ADD COLUMN project_id text NOT NULL DEFAULT 'prj_default',ADD COLUMN environment_id text NOT NULL DEFAULT 'env_default';
CREATE TABLE gateway_change_request (
    id uuid PRIMARY KEY,action text NOT NULL,target text NOT NULL,payload jsonb NOT NULL,payload_sha256 text NOT NULL,
    reason text NOT NULL,requested_by uuid NOT NULL,requested_by_name text NOT NULL,approvals jsonb NOT NULL DEFAULT '[]',
    status text NOT NULL CHECK(status IN ('pending_second_approval','approved','applied')),
    result jsonb,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),applied_at timestamptz
);
CREATE TABLE gateway_budget_account (
    organization_id text NOT NULL,project_id text NOT NULL,environment_id text NOT NULL,
    limit_microunits bigint CHECK(limit_microunits>=0),reserved_microunits bigint NOT NULL DEFAULT 0 CHECK(reserved_microunits>=0),
    settled_microunits bigint NOT NULL DEFAULT 0 CHECK(settled_microunits>=0),version bigint NOT NULL DEFAULT 1,updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(organization_id,project_id,environment_id)
);
CREATE TABLE gateway_budget_reservation (
    request_id uuid PRIMARY KEY,organization_id text NOT NULL,project_id text NOT NULL,environment_id text NOT NULL,
    amount_microunits bigint NOT NULL CHECK(amount_microunits>=0),created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(organization_id,project_id,environment_id) REFERENCES gateway_budget_account
);
CREATE TABLE gateway_budget_event (
    id uuid PRIMARY KEY,organization_id text NOT NULL,project_id text NOT NULL,environment_id text NOT NULL,
    request_id uuid,event_type text NOT NULL CHECK(event_type IN ('reservation_created','settled','released','adjustment')),
    reserved_delta_microunits bigint NOT NULL,settled_delta_microunits bigint NOT NULL,evidence_source text NOT NULL,
    billing_mode text,reason text,actor_id uuid,input_tokens bigint NOT NULL DEFAULT 0,output_tokens bigint NOT NULL DEFAULT 0,
    cache_read_tokens bigint NOT NULL DEFAULT 0,cache_write_tokens bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX gateway_budget_event_scope_time_idx ON gateway_budget_event(organization_id,project_id,environment_id,created_at DESC);
INSERT INTO gateway_budget_account(organization_id,project_id,environment_id,settled_microunits)
SELECT 'org_local','prj_default','env_default',COALESCE(round(sum(cost_estimate)*1000000),0)::bigint FROM gateway_usage;
INSERT INTO gateway_budget_event(id,organization_id,project_id,environment_id,event_type,reserved_delta_microunits,settled_delta_microunits,evidence_source,reason)
SELECT '00000000-0000-0000-0000-000000000009','org_local','prj_default','env_default','adjustment',0,settled_microunits,'schema_migration','Opening balance from existing Java usage estimates' FROM gateway_budget_account;
CREATE FUNCTION reject_budget_event_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Budget events are append-only';
END;
$$;
CREATE TRIGGER gateway_budget_event_immutable BEFORE UPDATE OR DELETE ON gateway_budget_event FOR EACH ROW EXECUTE FUNCTION reject_budget_event_mutation();
