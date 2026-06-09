ALTER TABLE gateway_user DROP CONSTRAINT gateway_user_role_check;
ALTER TABLE gateway_user ADD CONSTRAINT gateway_user_role_check CHECK (role IN ('admin', 'user', 'viewer'));
ALTER TABLE gateway_user ADD COLUMN email text NOT NULL DEFAULT '';
ALTER TABLE gateway_user ADD COLUMN status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled', 'suspended'));
ALTER TABLE gateway_user ADD COLUMN last_login_at timestamptz;
