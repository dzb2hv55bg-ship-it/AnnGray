# MOMENTO 0.35.0 — Account-scoped WorkManager resume

- Added WorkManager dependency and a connected-network constrained worker.
- Worker only sends explicitly QUEUED moments from the server-verified account-specific SQLite database.
- Reuses durable idempotency UUID, checks owner and session before each upload.
- Permanent 4xx errors remain FAILED; transient errors return to QUEUED and WorkManager retries.
- This is a conservative resume foundation, not a complete automatic offline sync feature.
- Outstanding: UI enqueue integration, token refresh coordination, Android Gradle build, device testing, real PostgreSQL.
