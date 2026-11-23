# MOMENTO 0.37.0 — corrective audit

- Fixed Android camera accepting 1–1999 ms clips even though the server rejects clips shorter than 2000 ms.
- Bounded WorkManager retries for expired credentials and transient upload failures. Original request UUID is preserved.
- Bumped Android and backend versions.
- Added source regression assertions to the project verifier.

## Limitations
- Worker does not refresh expired credentials itself; reopening the app may be required.
- Android compilation and PostgreSQL integration have not been tested.
- Structural assertions are not Kotlin compiler tests.
