# MOMENTO 0.16.0 — Auth boundary hardening

- Fixed malformed refresh tokens causing HTTP 500: now HTTP 401.
- Invalid email/phone input now maps to HTTP 422 instead of HTTP 500.
- Added refresh token shape regression test.
- Access token decoder rejects non-object JSON and invalid exp/iat ordering.

## Not verified
No Android Gradle build, PostgreSQL integration, provider credentials, email/SMS gateway or physical-device sign-in were available. No 95% claim.
