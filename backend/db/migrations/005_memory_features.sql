-- Feature storage foundations. This migration does not create public API access.
-- Avatar uses existing users.avatar_media_id; enforce ownership, readiness and 2-5s at write time.
CREATE TABLE time_capsules (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 moment_id UUID NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
 release_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 opened_at TIMESTAMPTZ,
 cancelled_at TIMESTAMPTZ,
 UNIQUE(owner_id,moment_id,release_at),
 CHECK(release_at > created_at)
);
CREATE INDEX time_capsules_owner_release_idx ON time_capsules(owner_id,release_at) WHERE cancelled_at IS NULL;
-- Consent is per moment and film; no implicit inclusion of private moments.
CREATE TABLE room_films (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
 created_by UUID NOT NULL REFERENCES users(id),
 title VARCHAR(100) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK(status IN ('DRAFT','RENDERING','READY','FAILED','DELETED'))
);
CREATE TABLE room_film_clips (
 film_id UUID NOT NULL REFERENCES room_films(id) ON DELETE CASCADE,
 moment_id UUID NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
 sequence_no INTEGER NOT NULL CHECK(sequence_no >= 0),
 consented_by UUID NOT NULL REFERENCES users(id),
 consented_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(film_id,moment_id),
 UNIQUE(film_id,sequence_no)
);
