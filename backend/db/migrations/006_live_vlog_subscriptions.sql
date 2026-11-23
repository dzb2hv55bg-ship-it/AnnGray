-- Separate visibility, publishing and completion; never infer one from another.
ALTER TABLE spaces ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ;
ALTER TABLE spaces ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;
CREATE TABLE IF NOT EXISTS room_subscriptions (
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
  notify BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(user_id, space_id)
);
CREATE INDEX IF NOT EXISTS room_subscriptions_room_idx ON room_subscriptions(space_id);
CREATE TABLE IF NOT EXISTS author_subscriptions (
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  author_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  notify BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(user_id, author_id),
  CHECK (user_id <> author_id)
);
CREATE TABLE IF NOT EXISTS room_updates (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
  moment_id UUID NOT NULL UNIQUE REFERENCES moments(id) ON DELETE CASCADE,
  author_id UUID NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS room_updates_feed_idx ON room_updates(created_at DESC, id DESC);
