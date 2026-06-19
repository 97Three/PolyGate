ALTER TABLE gateway_request ADD COLUMN fallback_from_provider text,ADD COLUMN dispatched_at timestamptz;
