-- MOMENTO-owned session lifecycle. Refresh tokens are stored only as keyed hashes.
ALTER TABLE auth_sessions
    ADD COLUMN rotated_from UUID REFERENCES auth_sessions(id),
    ADD COLUMN replaced_by UUID REFERENCES auth_sessions(id),
    ADD COLUMN revoked_reason VARCHAR(40);

CREATE INDEX auth_sessions_active_expiry_idx
    ON auth_sessions (expires_at)
    WHERE revoked_at IS NULL;

-- A session row represents one refresh-token generation. Rotation revokes the old
-- row and creates a replacement row in the same transaction; replay is rejected.
