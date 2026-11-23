# MOMENTO 0.38.0 — background refresh hardening

- Worker now refreshes an expiring access token before uploading queued clips.
- Revalidates refreshed token against `/v1/auth/me` and refuses mismatched account ownership.
- Rechecks the encrypted session before writing refreshed credentials to avoid overwriting an unrelated login.
- Bounded retries remain in place.

## Known limitations

- The foreground app refresh path still has its own lifecycle and can race with the worker; this requires shared session coordination and device integration tests.
- No Android SDK build, physical device, or PostgreSQL integration test performed.
- Refresh failures can require the user to reopen the app and reauthenticate.
