# MOMENTO 0.24.0 — one-year time capsules, backend MVP

- POST /v1/me/capsules: authenticated, accepts `momentId` and UTC `releaseAt`, minimum one calendar year, validates ownership, visibility, moderation, 2–5s duration and server-side release threshold.
- GET /v1/me/capsules: authenticated, lists own capsules; `moment_id` stays null until the database release time. No media URLs are provided.
- Duplicate capsules with the same moment and release time are rejected without leaking ownership.
- Includes pure validation and mocked-query tests. Real PostgreSQL integration and Android UI **not tested or implemented**.
- Existing migration 005_memory_features.sql contains time_capsules; apply it before enabling the API.
- Follow-up: PostgreSQL integration tests, expiry/revocation, Android UI, media access authorization after release.
