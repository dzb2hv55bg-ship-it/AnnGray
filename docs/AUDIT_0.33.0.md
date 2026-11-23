# MOMENTO 0.33.0 — account isolation (Android)

- LocalMomentDatabase now uses a per-account SHA-256-named SQLite database.
- Account identity is obtained via authenticated GET /v1/auth/me, not by trusting an unverified JWT claim.
- Archive is inaccessible until the account identity is verified; this intentionally requires a connection on first load and after a token change. Offline access without verification is not yet solved.
- The old unscoped `momento_local.db` is **quarantined**, not deleted or silently assigned to an account. Recovery/migration requires explicit ownership confirmation.
- LocalSpaceDatabase now also uses a per-account SQLite filename; other cached items still require a broader privacy audit.
- Android build and end-to-end tests are not available in this environment.
