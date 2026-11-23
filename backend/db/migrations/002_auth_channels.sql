-- MOMENTO multi-channel authentication foundation.
-- Apply after 001_initial_schema.sql. Never store OTPs or raw refresh tokens.

CREATE TABLE user_contacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    contact_type VARCHAR(10) NOT NULL CHECK (contact_type IN ('EMAIL', 'PHONE')),
    value_hash BYTEA NOT NULL,
    value_ciphertext BYTEA NOT NULL,
    encryption_key_version VARCHAR(32) NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (contact_type, value_hash),
    UNIQUE (user_id, contact_type, id)
);
CREATE INDEX user_contacts_user_idx ON user_contacts (user_id, contact_type);
CREATE UNIQUE INDEX user_contacts_one_primary_idx
    ON user_contacts (user_id, contact_type) WHERE is_primary = TRUE;

CREATE TABLE auth_challenges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    purpose VARCHAR(20) NOT NULL CHECK (purpose IN ('SIGN_IN', 'LINK_CONTACT', 'RECOVER_ACCOUNT')),
    contact_type VARCHAR(10) NOT NULL CHECK (contact_type IN ('EMAIL', 'PHONE')),
    contact_hash BYTEA NOT NULL,
    contact_ciphertext BYTEA NOT NULL,
    encryption_key_version VARCHAR(32) NOT NULL,
    otp_hash BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 10),
    send_count INTEGER NOT NULL DEFAULT 1 CHECK (send_count BETWEEN 1 AND 5),
    last_sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX auth_challenges_lookup_idx
    ON auth_challenges (contact_type, contact_hash, created_at DESC);
CREATE INDEX auth_challenges_expiry_idx ON auth_challenges (expires_at) WHERE consumed_at IS NULL;

CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash BYTEA NOT NULL UNIQUE,
    device_label VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX auth_sessions_user_idx ON auth_sessions (user_id, created_at DESC);

CREATE TABLE auth_link_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    issuer TEXT NOT NULL,
    subject VARCHAR(255) NOT NULL,
    state_hash BYTEA NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX auth_link_attempts_user_idx ON auth_link_attempts (user_id, created_at DESC);
