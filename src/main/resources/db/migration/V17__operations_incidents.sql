CREATE TABLE gateway_ops_incident (
 id uuid PRIMARY KEY,event_key text NOT NULL,organization_id text NOT NULL,project_id text NOT NULL,environment_id text NOT NULL,
 document jsonb NOT NULL,UNIQUE(organization_id,project_id,environment_id,event_key)
);
CREATE TABLE gateway_ops_evidence(id uuid PRIMARY KEY,incident_id uuid NOT NULL REFERENCES gateway_ops_incident(id),document jsonb NOT NULL);
CREATE TABLE gateway_ops_timeline(id uuid PRIMARY KEY,incident_id uuid NOT NULL REFERENCES gateway_ops_incident(id),document jsonb NOT NULL);
CREATE TABLE gateway_ops_heartbeat(instance_id uuid PRIMARY KEY,document jsonb NOT NULL,received_at timestamptz NOT NULL DEFAULT now());
