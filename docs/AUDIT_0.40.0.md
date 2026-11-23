# MOMENTO 0.40.0 — Upload queue token-expiry audit

## Fixes
- Each queued item refreshes/checks the server-verified session before sending. Previously a long queue reused the token captured when the worker started.
- HTTP 401 during upload leaves the item QUEUED and follows the bounded retry policy, rather than marking it permanently failed.
- Upload UUID remains stable through retries.
- Added regression assertions to the offline verifier.

## Limits
- A revoked token may still cause bounded retries; user must reauthenticate.
- Worker is process-synchronized only; concurrent Android processes are not covered.
- No Gradle/Android SDK or running PostgreSQL in this environment: Android build and end-to-end uploads remain unverified.
