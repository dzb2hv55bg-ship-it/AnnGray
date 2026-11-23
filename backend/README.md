# MOMENTO API foundation (0.12.0)

This is an initial server implementation, not a deployed service or production-ready backend.

## Implemented routes

- `GET /health` — process and PostgreSQL health.
- `POST /v1/auth/session`, `POST /v1/auth/refresh`, `POST /v1/auth/logout`, `GET /v1/auth/me` — MOMENTO session lifecycle.
- `GET /v1/spaces` — rooms of the authenticated user.
- `GET /v1/discovery/spaces?q=...` — public room catalogue only; private and archived rooms are excluded.
- `POST /v1/spaces/{spaceId}/join` — direct join for `OPEN` public rooms or create a pending request for `APPROVAL` rooms. `INVITE_ONLY` rooms reject catalogue joins.
- `GET /v1/spaces/{spaceId}/join-requests` — pending requests for owner/admin/moderator.
- `POST /v1/spaces/{spaceId}/join-requests/{requestId}/review` — approve/reject; approval rechecks age compatibility and blocks against room members.
- `POST /v1/spaces` — create a room and owner membership in one transaction.
- `POST /v1/spaces/{spaceId}/invites` — issue a cryptographically random invite; only its SHA-256 hash is stored; raw token is returned once. Owners, admins and moderators may create invites.
- `DELETE /v1/spaces/{spaceId}/invites/{inviteId}` — revoke an invite. Only owners and admins may revoke it.
- `POST /v1/invites/{token}/accept` — transactionally validate expiry/revocation/use limit, check block relations and age profiles, and add membership.
- `GET /v1/spaces/{spaceId}/moments` — member-only chronological timeline, restricted to visible moments and ready media.

## Local setup

1. Install Node.js 22+, PostgreSQL client tools, Docker Compose and FFmpeg.
2. Start PostgreSQL with `docker compose up -d postgres` from this directory.
3. Copy `.env.example` to `.env`. Configure `PUBLIC_APP_URL` and generate independent secrets for the authentication and OTP settings.
4. Load the environment and apply all migrations in order to a fresh local database:

   ```bash
   set -a
   source .env
   set +a
   for migration in db/migrations/*.sql; do
     psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f "$migration" || break
   done
   ```

5. Run `npm ci`, then `npm test` and `npm start`. Check `GET /ready` before connecting an Android build.

### Test server container

Build from this directory with `docker build -t momento-api .`. The image includes `ffprobe` for upload validation and runs the API as an unprivileged user. Configure the deployment to inject the variables from `.env.example`, connect to a migrated PostgreSQL database, mount persistent writable storage at `/var/lib/momento/media`, and terminate HTTPS at the ingress. The health check uses `GET /ready`.

Use the deployed HTTPS API origin as the Android build's `momentoApiBaseUrl` or set the repository Actions variable `MOMENTO_API_URL`. Configure the selected login providers separately.

### Authentication (0.13.0)

Provider endpoints:
- `POST /v1/auth/providers/google/nonce` issues a one-time five-minute nonce required by Google ID token sign-in.
- `POST /v1/auth/providers/google` with `{ "idToken": "...", "deviceLabel": "Android" }`.
- `POST /v1/auth/providers/yandex` with `{ "accessToken": "...", "deviceLabel": "Android" }`.
- `POST /v1/auth/providers/vk` with `{ "accessToken": "...", "deviceLabel": "Android" }`.
- `POST /v1/auth/otp/request` with `{ "contactType": "EMAIL|PHONE", "value": "..." }`.
- `POST /v1/auth/otp/verify` with `{ "challengeId": "...", "code": "123456" }`.
- `POST /v1/auth/link/otp/request` and `/verify` — link a verified email/phone to the currently authenticated MOMENTO account.
- `POST /v1/auth/link/providers/{google|yandex|vk}` — link a provider identity from an active MOMENTO session after validating the provider token.

All successful channels create the same MOMENTO session format. Google ID tokens are validated against Google's signing keys and the configured server client ID. Yandex and VK access tokens are resolved by calling their profile APIs from the backend. Email/SMS codes are short-lived, HMAC-hashed and rate-limited; contact values are AES-256-GCM encrypted and indexed by a separate HMAC.

Set provider configuration on the backend only:
`GOOGLE_SERVER_CLIENT_ID`, `YANDEX_CLIENT_ID`, `VK_CLIENT_ID`.
OTP security keys: `OTP_HMAC_SECRET`, `CONTACT_HASH_SECRET`, `CONTACT_ENCRYPTION_KEY` (base64 for exactly 32 random bytes).
Delivery adapters: `EMAIL_DELIVERY_URL`, `EMAIL_DELIVERY_API_KEY`, `SMS_DELIVERY_URL`, `SMS_DELIVERY_API_KEY`. The URLs must be HTTPS and accept the JSON contract documented in `.env.example`.

The Android sign-in screen is wired to the Google, Yandex and VK SDKs and to the OTP endpoints. Live sign-in still requires provider app registrations, matching client IDs, a deployed API and real email/SMS delivery credentials.

### Authentication lifecycle (0.11.0)

- `POST /v1/auth/session` accepts a bearer token from the one upstream OIDC issuer configured by `JWT_ISSUER`, `JWT_AUDIENCE` and `JWT_JWKS_URL`. It provisions/maps the external `(issuer, subject)` identity and returns MOMENTO-owned tokens.
- Protected API calls use only MOMENTO access tokens (HS256, 15-minute lifetime). The API checks the associated session row on each request, so logout/revocation takes effect immediately.
- `POST /v1/auth/refresh` accepts `{ "refreshToken": "..." }`, rotates it transactionally, and invalidates the previous token. Reuse of a rotated token revokes active descendants in that rotation chain.
- `POST /v1/auth/logout` revokes a session by its refresh token, including when the short-lived access token has expired. `GET /v1/auth/me` returns the current account summary.
- Refresh tokens are random opaque values; only a peppered HMAC is stored in PostgreSQL. Keep the pepper and JWT secret in a managed secret store in production.

**Production activation is not complete:** dedicated Google, Yandex, VK and OTP routes are implemented, but they have not been verified against real registered apps, a live PostgreSQL deployment or actual mail/SMS vendors. Configure provider apps and delivery services, apply all database migrations and complete the production QA checklist before enabling public sign-in.

## Important safety and deployment gaps

- No development-header or anonymous authentication bypass exists. Dedicated provider routes validate provider-issued tokens; the legacy upstream OIDC bridge is optional.
- Account onboarding must create and verify `user_age_profiles`; invite acceptance fails closed when the joining account has no age profile. The current same-age-band restriction is a conservative technical placeholder, **not** a final child-safety or legal policy.
- Rate limiting, abuse detection, invite brute-force controls, audit events, moderation workflows, request tracing and production secrets management remain to be added before public exposure.
- `PUBLIC_APP_URL` must point to an app-controlled invite landing page. The API does not host a web invite page.
- The Android room transport remains HTTPS-only and is not yet wired into the room Compose screens. The authentication transport is wired into the sign-in screen.
- No media upload/storage route is implemented in this increment. No public video URLs are generated.
- The API does not yet implement idempotency-key persistence despite the broader API contract. Clients must not blindly retry non-idempotent create operations until that layer is added.
- Configure TLS at the ingress/reverse proxy, restrict database network access, and use managed secrets in deployment.

The SQL migration is the source of truth for the current schema. Apply it to a disposable local database first; do not run it against production data without a reviewed migration plan.


### Authentication hardening (0.13.0)

- OTP challenge reservation now takes a PostgreSQL advisory transaction lock per normalized contact before checking the resend/hourly counters and inserting the challenge. Concurrent requests for the same contact can no longer both pass the pre-insert rate-limit check.
- Provider upstream throttling and transient HTTP statuses (408, 425, 429 and 5xx) are reported as temporary provider unavailability rather than invalid credentials.
- The Android app schedules MOMENTO refresh before the access token expires while the app process is alive. Transient network/server errors are retried; a definitive 401 clears the invalid session.
- This release still requires PostgreSQL integration tests, a real Android Gradle build, provider app credentials and live provider/delivery checks.
