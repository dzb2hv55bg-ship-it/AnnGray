-- One-time server-issued nonces for Google ID token sign-in.
CREATE TABLE auth_provider_nonces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nonce_hash BYTEA NOT NULL UNIQUE,
    provider VARCHAR(20) NOT NULL CHECK (provider IN ('google')),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX auth_provider_nonces_expiry_idx
    ON auth_provider_nonces (expires_at) WHERE consumed_at IS NULL;
