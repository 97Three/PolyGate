ALTER TABLE gateway_usage ADD COLUMN actual_cost numeric(24,8),ADD COLUMN billable_cost numeric(24,8),ADD COLUMN pricing_evidence jsonb;
