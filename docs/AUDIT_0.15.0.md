# MOMENTO 0.15.0 — archive and code audit

Scope: original archive 0.14.0, Android scaffold and Node backend. Product decisions retained: blue UI, 2–5-second moments (hard cap 5 s), private archive, public/private rooms, offline-first recording, no infinite public feed.

## Changes
- Strict HTTP JSON object parser: invalid JSON and scalar/array/null payloads return 400, oversized payloads return 413. Previously some payloads could cause TypeErrors/500s.
- Removed duplicate unreachable logout route. Refresh-token logout remains the active logout API contract.
- Existing users' display names are no longer overwritten by their identity provider during subsequent logins.
- Added focused parser tests.

## Verification boundaries
- `npm test`, `npm run check`, `python3 scripts/verify_project.py`, `unzip -t` are executable checks.
- Backend PostgreSQL integration tests, OAuth provider E2E, email/SMS delivery and Android Gradle/device builds still require infrastructure.
- Source repository for MOMENTO is not present in the archive; third-party references are documented in `docs/THIRD_PARTY_REFERENCES.md`. This audit does not claim upstream source/version verification.
