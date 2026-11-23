# MOMENTO 0.39.0 — shared session refresh

- Added a single process-wide refresh-token rotation path for UI and WorkManager.
- Refreshed identity is verified against `/v1/auth/me` before writing to the vault.
- CAS-style token comparison prevents stale worker writes and avoids clearing a newer session on a 401 response.
- Added source regression assertions.

Limitations: no Android Gradle compile, device test or PostgreSQL integration test. Process-level lock does not coordinate multiple Android processes. WorkManager retry scheduling still requires device testing.
