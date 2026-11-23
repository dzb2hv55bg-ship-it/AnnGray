-- MOMENTO MVP database schema (PostgreSQL).
-- The API must enforce authorization on every request. Do not expose this database directly.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE space_kind AS ENUM ('CIRCLE', 'INTEREST_ROOM');
CREATE TYPE space_visibility AS ENUM ('PRIVATE', 'PUBLIC');
CREATE TYPE join_policy AS ENUM ('INVITE_ONLY', 'OPEN', 'APPROVAL');
CREATE TYPE member_role AS ENUM ('OWNER', 'ADMIN', 'MODERATOR', 'MEMBER');
CREATE TYPE media_status AS ENUM ('PENDING_UPLOAD', 'READY', 'QUARANTINED', 'DELETE_PENDING', 'DELETED');
CREATE TYPE moment_visibility AS ENUM ('PRIVATE', 'SPACE');
CREATE TYPE moderation_state AS ENUM ('PENDING', 'VISIBLE', 'HIDDEN', 'REMOVED');

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name VARCHAR(60) NOT NULL,
    avatar_media_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

-- OIDC subjects are opaque strings, not guaranteed UUIDs. Keep external
-- identity keys separate from the application's internal user UUID.
CREATE TABLE auth_identities (
    issuer TEXT NOT NULL,
    subject VARCHAR(255) NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (issuer, subject)
);
CREATE INDEX auth_identities_user_idx ON auth_identities (user_id);

CREATE TABLE user_age_profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    birth_date DATE NOT NULL,
    age_band VARCHAR(20) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE spaces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    kind space_kind NOT NULL,
    visibility space_visibility NOT NULL,
    join_policy join_policy NOT NULL,
    age_policy_key VARCHAR(40) NOT NULL DEFAULT 'GENERAL',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at TIMESTAMPTZ,
    CHECK ((visibility = 'PRIVATE' AND join_policy = 'INVITE_ONLY') OR visibility = 'PUBLIC')
);
CREATE INDEX spaces_discovery_idx ON spaces (visibility, created_at DESC) WHERE archived_at IS NULL;
CREATE INDEX spaces_owner_idx ON spaces (owner_id, created_at DESC);

CREATE TABLE space_members (
    space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role member_role NOT NULL DEFAULT 'MEMBER',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    muted_at TIMESTAMPTZ,
    PRIMARY KEY (space_id, user_id)
);
CREATE INDEX space_members_user_idx ON space_members (user_id, joined_at DESC);

CREATE TABLE space_invites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    created_by UUID NOT NULL REFERENCES users(id),
    token_hash BYTEA NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    max_uses INTEGER,
    use_count INTEGER NOT NULL DEFAULT 0,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (max_uses IS NULL OR max_uses > 0),
    CHECK (use_count >= 0)
);

CREATE TABLE join_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (space_id, user_id),
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
);

CREATE TABLE media_assets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id),
    storage_key TEXT UNIQUE,
    mime_type VARCHAR(100) NOT NULL,
    byte_size BIGINT,
    checksum_sha256 BYTEA,
    duration_ms INTEGER,
    width INTEGER,
    height INTEGER,
    status media_status NOT NULL DEFAULT 'PENDING_UPLOAD',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    uploaded_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    CHECK (byte_size IS NULL OR byte_size > 0),
    CHECK (duration_ms IS NULL OR duration_ms BETWEEN 1 AND 5000),
    CHECK (width IS NULL OR width > 0),
    CHECK (height IS NULL OR height > 0)
);
CREATE INDEX media_assets_owner_idx ON media_assets (owner_id, created_at DESC);

ALTER TABLE users ADD CONSTRAINT users_avatar_media_fk
    FOREIGN KEY (avatar_media_id) REFERENCES media_assets(id) ON DELETE SET NULL;

CREATE TABLE moments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id),
    media_asset_id UUID NOT NULL UNIQUE REFERENCES media_assets(id),
    captured_at TIMESTAMPTZ NOT NULL,
    duration_ms INTEGER NOT NULL,
    caption VARCHAR(280),
    visibility moment_visibility NOT NULL DEFAULT 'PRIVATE',
    moderation moderation_state NOT NULL DEFAULT 'VISIBLE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CHECK (duration_ms BETWEEN 1 AND 5000)
);
CREATE INDEX moments_owner_timeline_idx ON moments (owner_id, captured_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX moments_moderation_idx ON moments (moderation, created_at DESC) WHERE deleted_at IS NULL;

-- One uploaded asset can be shared to multiple spaces without duplicating the file.
CREATE TABLE moment_audiences (
    moment_id UUID NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
    space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    granted_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (moment_id, space_id)
);
CREATE INDEX moment_audiences_space_idx ON moment_audiences (space_id, created_at DESC);

CREATE TABLE comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    moment_id UUID NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES users(id),
    body VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX comments_moment_idx ON comments (moment_id, created_at ASC) WHERE deleted_at IS NULL;

CREATE TABLE reactions (
    moment_id UUID NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reaction VARCHAR(16) NOT NULL DEFAULT 'HEART',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (moment_id, user_id),
    CHECK (reaction IN ('HEART', 'SMILE', 'WOW'))
);

CREATE TABLE user_blocks (
    blocker_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);

CREATE TABLE safety_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES users(id),
    target_user_id UUID REFERENCES users(id),
    target_space_id UUID REFERENCES spaces(id),
    target_moment_id UUID REFERENCES moments(id),
    reason VARCHAR(40) NOT NULL,
    details VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(target_user_id, target_space_id, target_moment_id) = 1)
);
CREATE INDEX safety_reports_queue_idx ON safety_reports (status, created_at ASC);

CREATE TABLE moderation_actions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    moderator_id UUID NOT NULL REFERENCES users(id),
    target_user_id UUID REFERENCES users(id),
    target_space_id UUID REFERENCES spaces(id),
    target_moment_id UUID REFERENCES moments(id),
    action VARCHAR(40) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE upload_idempotency (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_request_id UUID NOT NULL,
    media_asset_id UUID REFERENCES media_assets(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, client_request_id)
);
