ALTER TABLE spaces
  ADD COLUMN event_starts_at timestamptz,
  ADD COLUMN event_ends_at timestamptz,
  ADD CONSTRAINT event_room_dates CHECK (
    (kind = 'EVENT' AND event_starts_at IS NOT NULL AND event_ends_at IS NOT NULL
      AND event_starts_at < event_ends_at)
    OR (kind <> 'EVENT' AND event_starts_at IS NULL AND event_ends_at IS NULL)
  ),
  ADD CONSTRAINT secret_room_private CHECK (
    kind <> 'SECRET' OR (visibility = 'PRIVATE' AND join_policy = 'INVITE_ONLY')
  );
