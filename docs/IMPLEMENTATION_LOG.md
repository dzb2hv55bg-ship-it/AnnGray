# MOMENTO implementation log

## 0.2.0 — durable local archive pass

### Implemented in source
- Added `LocalMomentDatabase`, backed by Android SQLite, as a durable local index for saved MP4 clips.
- A local moment stores an ID, private file path, capture timestamp, measured duration, byte size and upload state.
- The database rejects durations outside 1–5,000 ms and rejects missing/empty files.
- The archive now reads its records from the database rather than rebuilding its entire index from a directory scan on every app launch.
- Stale database rows are cleaned up if their local file no longer exists.
- Camera output is inspected with `MediaMetadataRetriever`; clips that cannot be measured or exceed 5,000 ms are rejected rather than silently accepted.
- The camera timer stops slightly before the hard limit to leave a small finalization margin; measured file duration remains the final check.
- Local upload states are stored (`LOCAL_ONLY`, `QUEUED`, `UPLOADING`, `UPLOADED_PRIVATE`, `FAILED`) so the offline upload queue can be added without changing the basic record shape.

### Deliberate limitations
- This pass uses Android's built-in SQLite API, not Room. It avoids adding a code-generation plugin before the project has a reproducible Gradle build. A migration to Room is possible later, with the existing SQLite schema as the migration source.
- There is no upload worker yet: there is no production API or object-storage endpoint to send a clip to. Marking a clip as uploaded before a real server confirms it would be incorrect.
- The camera currently records without audio. Microphone permission and audio capture need a separate explicit implementation and device test.
- No APK build or physical-device test is claimed. The current execution environment has no Android SDK or Gradle installation.

## Validation status
- Source files and project configuration were inspected.
- XML, TOML, OpenAPI YAML and SQL syntax checks are run as part of the local verification pass where available.
- Pure Kotlin domain rules can be compiled independently; Android framework classes cannot be compiled here without the Android SDK.
- Required next external validation: open in Android Studio, Gradle sync, assemble debug APK, then test capture/archive on at least one physical Android device.

## 0.3.0 — offline room creation prototype

### Implemented in source
- Added a separate local SQLite database for room metadata (`momento_spaces.db`).
- Added local room creation with a required 1–60 character name, generated stable UUID and creation timestamp.
- Added a persistent “My rooms” list that restores locally created rooms after app restart.
- Added explicit `LOCAL_ONLY` sync state. The UI clearly tells the user that invitations and shared timelines are not connected yet; no fake invite links or cross-device behavior are presented as working.
- Extended the structural verification script to validate the room table constraints and UI wiring.

### Deliberate limitations
- Room creation is currently local to this installation. A room cannot yet be discovered or joined from another device.
- Invite links/codes must be server-issued, revocable and rate-limited; generating a client-only code would create a misleading and insecure invitation flow.
- Shared room timeline and membership require the backend and authorization checks before they can be enabled.

### Validation
- `python3 scripts/verify_project.py` passes, including SQLite checks for valid room names and the five-second moment ceiling.
- Basic delimiter sanity check passed for the edited Compose screen.
- Android Gradle compilation and device testing remain unverified because this environment has no Android SDK/Gradle installation.

## 0.4.0 — backend foundation for rooms and invitations

### Implemented in source
- Added a Node.js 22+ API service using PostgreSQL (`pg`) and OIDC JWT verification through issuer-configured JWKS (`jose`). There is no anonymous or development-header authentication bypass.
- Added `/health`, authenticated room listing and room creation. Room row and owner membership are created in one database transaction.
- Added server-issued invitation links with 256-bit cryptographically random tokens. Only SHA-256 token hashes are persisted; raw tokens are returned only in the creation response.
- Invitation redemption locks the invite row in a transaction, checks expiry/revocation/use limits, checks block relationships, requires an age profile, and applies a conservative same-age-band compatibility baseline.
- Added member-only room timeline query; only non-deleted, visible moments with media marked `READY` are returned.
- Added local PostgreSQL Compose configuration, environment template, backend setup/safety notes and Node built-in tests for token handling, bearer parsing, integer bounds and room validation.

### Deliberate limitations
- This is server source code, not a deployed or externally reachable API. A real OIDC provider and its JWT/JWKS settings have not been selected or configured.
- Age-band matching is a temporary fail-closed technical baseline, not a validated child-safety policy or legal interpretation. Account onboarding/age-profile creation is not yet implemented, so invite acceptance remains unavailable until that trusted flow exists.
- Rate limiting, invite abuse controls, idempotency persistence, public-room discovery, join approvals, moderation operations, media upload/storage, push notifications and Android API integration remain future work.
- The Android app still uses local room storage. The new backend has not been compiled against or connected to the Android client.

### Validation
- Node source syntax checks and unit tests are run locally without requiring PostgreSQL.
- PostgreSQL integration tests, live OIDC verification, Android Gradle build and physical-device testing are not claimed.


## 0.5.0 — identity mapping and safer room timeline

### Implemented in source
- Replaced the incorrect assumption that an OIDC `sub` claim must be a UUID. OIDC `(issuer, subject)` values are now mapped to an internal MOMENTO UUID through `auth_identities`.
- First-login provisioning is serialized with a PostgreSQL advisory transaction lock so concurrent first requests for one identity do not create duplicate users.
- Deleted internal accounts are not silently recreated by a later login.
- Fixed age compatibility lookup to use `LEFT JOIN`; a missing age profile for any existing room member now blocks invitation acceptance instead of being omitted by an inner join.
- Added stable timeline pagination using the `(captured_at, id)` composite cursor, preventing moments with identical capture timestamps from being skipped between pages. Timestamp values are returned as PostgreSQL text to preserve microsecond precision instead of JavaScript Date millisecond truncation.
- Added validation requiring both cursor fields together and a UUID tie-breaker; documented the additional `beforeId` query parameter in OpenAPI.

### Deliberate limitations
- The database migration is updated for a fresh scaffold database. Existing deployed databases need a separately reviewed forward migration; do not rerun the edited initial migration against a database where it has already been applied.
- Age-band compatibility remains a temporary fail-closed placeholder. Trusted age onboarding and the final child-safety policy are not implemented.
- No real OIDC provider, PostgreSQL instance or Android API connection has been configured in this environment.

### Validation
- Added unit coverage for complete/invalid composite timeline cursors and source checks for OIDC identity mapping.
- Node syntax checks and dependency-free unit tests are run by the verification script. PostgreSQL integration, Android Gradle build and physical-device testing remain unverified.


## 0.6.0 — Android API transport layer

### Implemented in source
- Added `MomentoApiClient` with HTTPS-only requests for server room listing, private-circle creation, invitation creation and invitation acceptance.
- Access tokens are supplied by the caller for each request; the client does not persist or log credentials.
- Added bounded timeouts, JSON request/response handling and typed HTTP API errors.
- Added Android `INTERNET` permission.
- Made the API base URL configurable through the Gradle property `momentoApiBaseUrl`, defaulting to a non-routable example host so the app cannot accidentally send user data to an unknown service.
- Aligned server room-name validation with the local database limit of 60 characters.

### Deliberate limitations
- The API client is a transport layer only; screens remain local-first until an OIDC provider, real HTTPS API endpoint and token lifecycle are configured.
- There is no offline-to-server room reconciliation yet. Local UUIDs must not be treated as server room IDs.
- No Android Gradle build or emulator/device test is available in this environment. The Kotlin Android source remains uncompiled here.


## 0.7.0 — invite lifecycle and archived-room protection

- Added server-side invite revocation (`DELETE /v1/spaces/{spaceId}/invites/{inviteId}`). Only owners and admins can revoke; moderators may create invites but cannot revoke them.
- Invite creation now returns a stable invite ID alongside the one-time raw invite URL. The database continues to store only the token hash.
- Invite creation is denied for archived rooms; accepting invites for archived rooms is denied.
- Invite acceptance now reports whether membership was newly created or the user was already a member, avoiding a misleading `joined: true` response on repeat use.
- Added Android API client methods and DTO support for invite revocation.
- Corrected backend setup documentation: OIDC `sub` is opaque and is not required to be a UUID.
- Added unit tests for public-room policy validation and invite-revocation authorization.
- Verification remains source/unit-level; no live PostgreSQL, Android Gradle build, APK, or device test was run in this environment.


## 0.8.0 — public room discovery and join policies

### Implemented in source
- Added authenticated public-room discovery with bounded search. The query returns only non-archived `PUBLIC` rooms; private rooms are excluded at SQL level. Search wildcard characters are escaped and user input is parameterized.
- Added `POST /v1/spaces/{spaceId}/join`: `OPEN` rooms add membership, `APPROVAL` rooms create/reopen a pending join request, and `INVITE_ONLY` rooms reject direct joining. Existing membership is handled idempotently.
- Added moderator/admin/owner-only listing and review of pending join requests. Approval is transactional and rechecks age-profile compatibility and block relationships against existing room members.
- Reused a single fail-closed age compatibility helper for invitation acceptance, open joining and request approval.
- Added Android API transport methods for discovery, public joining, listing join requests and approving/rejecting requests. These remain transport-only; Compose screens are not connected.
- Tightened server route UUID matching and updated the OpenAPI contract and backend documentation.

### Deliberate limitations
- Trusted age onboarding/profile creation is still absent. Because the server fails closed, public joins and invite acceptance cannot complete until a trusted age-profile flow exists. Do not work around this with client-supplied age bands.
- Age-band matching is a temporary conservative technical placeholder, not a final child-safety policy or legal assessment.
- The API has not been exercised against a live PostgreSQL instance; SQL behavior needs integration tests on PostgreSQL before deployment.
- No real OIDC provider, deployed API, Android Gradle build, APK or device-to-device test is available in this environment.

### Validation
- Node syntax checks and 16 dependency-free unit/source tests pass.
- Structural project verification and ZIP integrity checks are run separately.

## 0.9.0 — Android shared-timeline transport

### Implemented in source
- Added typed Android transport models for remote moments, timeline cursors and timeline pages.
- Added `getSpaceTimeline` to `MomentoApiClient`, including page-size validation, UUID path validation and stable `(captured_at, id)` cursor parameters.
- The method consumes the existing authenticated member-only backend timeline endpoint; it does not create local fake timeline entries or bypass server authorization.
- Updated the Android app version to `0.9.0` and extended structural checks and QA notes for timeline pagination.

### Deliberate limitations
- The timeline transport is not yet wired into the Compose room screen.
- Media playback URLs are not yet available from the backend; the timeline currently carries metadata only.
- OIDC provider selection, token lifecycle, live PostgreSQL integration, Android Gradle compilation and physical-device testing remain outstanding.

### Validation
- Re-ran structural project verification and backend unit tests after the source change.
- Android Kotlin compilation remains unverified because this environment has no Gradle wrapper or Android SDK.


## 0.10.0 — unified multi-channel authentication foundation

### Implemented in source
- Recorded the five required sign-in channels: Yandex ID, Google, VK ID, email OTP and phone OTP.
- Added a provider-neutral authentication design centered on one internal MOMENTO user ID and external `(issuer, subject)` identities.
- Added migration `002_auth_channels.sql` for verified contacts, OTP challenges, refresh-token sessions and explicit external-identity linking attempts.
- Added input normalization for email and E.164 phone numbers, cryptographically generated six-digit OTPs, HMAC OTP hashing and constant-time comparison utilities, plus opaque refresh-token generation.
- Added dependency-free unit tests for normalization, OTP hashing/verification and token generation.

### Not yet implemented / requires configuration
- OAuth authorization and callbacks for Yandex ID, Google and VK ID, including provider-specific token exchange and validation.
- MOMENTO-owned access-token signing/JWKS endpoint, refresh rotation, logout and revocation endpoints.
- Email and SMS delivery adapters and their provider credentials.
- Auth UI and token lifecycle integration in the Android app.
- PostgreSQL execution/integration tests for migration 002.
- Provider app registration, package fingerprints, redirect/deep links and production credentials.

### Validation
- `node --check` passes for the new auth core.
- Backend unit suite: 20 tests passed.
- Structural verification includes assertions for the new migration and authentication utilities.
- No live provider login, OTP delivery, PostgreSQL migration execution, Android Gradle build or physical-device test is claimed.


## 0.11.0 — MOMENTO-owned sessions

- Added HS256 MOMENTO access tokens with issuer/audience checks and a 15-minute lifetime.
- Added opaque refresh tokens, peppered HMAC storage, transactional rotation, logout and per-request active-session validation.
- Added refresh-token rotation lineage and replay-family revocation migration 003.
- Added `/v1/auth/session`, `/v1/auth/refresh`, `/v1/auth/logout` and `/v1/auth/me`. The session exchange currently accepts the single upstream OIDC issuer configured by environment variables.
- Expanded auth unit tests for access-token signature, issuer/audience and expiry validation.
- Not implemented in this increment: dedicated Yandex/Google/VK provider adapters, email/SMS delivery, Android auth UI, PostgreSQL integration test run, provider credentials or production deployment.


## 0.12.0 — server-side provider and OTP adapters (partial auth integration)

### Implemented in source
- Added Google ID token validation with Google JWKS, issuer, audience, expiry and RS256 checks.
- Added Yandex access-token verification by calling Yandex ID user-information endpoint and checking the registered client ID.
- Added VK ID user-info verification through the VK ID endpoint with server-side app client ID.
- Added common provider identity provisioning into one internal MOMENTO `users.id` and unique `(issuer, subject)` mapping.
- Added email/phone OTP request and verification endpoints, five-minute expiry, per-contact resend/hourly limits, attempt limit, HMAC OTP storage, AES-256-GCM encrypted contacts and keyed contact hashes.
- Added HTTPS-only email/SMS delivery adapter contract; no mail/SMS credentials are embedded in source or APK.
- Added backend tests for contact normalization, keyed hashing, encryption, OTP delivery guardrails, Yandex and VK provider profile validation.
- Added Android Compose sign-in screen with Google Credential Manager, Yandex ID SDK 3.1.3, official VK ID SDK 2.7.3, and email/phone OTP.
- Added Android Keystore AES-GCM vault for MOMENTO tokens, startup refresh, logout and UI for linking all five channels to the current account.
- Added authenticated server routes for linking verified email/phone and Google/Yandex/VK identities without auto-merging accounts.
- Added migration `004_auth_provider_nonce.sql` for one-time five-minute Google ID token nonces and server-side replay rejection.
- Bumped Android app version metadata and backend package metadata to 0.12.0.

### Validation
- `node --check` passed for server, auth core, OTP adapter and provider adapter.
- Backend tests: 26 passed, 0 failed, 0 skipped. Google JWT signature/claim validation is implemented with `jose`, but was not live-tested here because dependencies were not installed and no Google test signing keys/app audience were configured.
- Structural checks are extended for new auth routes and modules.
- PostgreSQL migrations/queries were not executed against a live database.
- Android sign-in UI and provider SDK source integration are present, but Android Gradle build, APK and physical-device tests are not claimed. Live provider login and email/SMS delivery still require app credentials and configured delivery vendors.
- No real Yandex/Google/VK login or email/SMS delivery is claimed; provider app registrations and delivery credentials are not available in the source archive.


## 0.13.0 — authentication lifecycle and OTP throttling hardening

### Implemented in source
- Serialized OTP challenge reservation per normalized contact with a PostgreSQL advisory transaction lock. Rate-limit counters and challenge insertion now run in the same transaction, preventing concurrent requests from bypassing the per-contact resend/hourly checks.
- Classified upstream provider HTTP 408, 425, 429 and 5xx responses as temporary provider unavailability instead of reporting them as invalid credentials.
- Updated Android session refresh lifecycle to schedule refresh 60 seconds before access-token expiry while the app process is alive.
- Added retry behavior for transient refresh failures; only a definitive 401 clears the local MOMENTO session.
- Updated Android/backend version metadata to 0.13.0.

### Validation
- Backend unit tests: 28 passed, 0 failed, including tests that verify the OTP reservation lock precedes the rate-limit query/insertion and that a rate-limited reservation rolls back without inserting a challenge.
- `npm run check`: passed.
- `python scripts/verify_project.py`: passed; this also reruns the backend suite.
- Android Gradle build is still unavailable: the project archive has no Gradle wrapper, and this environment has no installed Gradle or Android SDK. No APK or physical-device test is claimed.
- PostgreSQL migration and concurrency behavior still require execution against a real PostgreSQL test instance.

## 0.14.0 — OTP purpose isolation
- Prevent cross-endpoint reuse of sign-in and contact-link challenges before any database mutation.
- Added unit test; see `docs/AUTHENTICATION_0.14.0.md`.

## 0.16.0
- Reject malformed refresh token before database hash operation (401).
- Map invalid contact input to 422.
- Harden access token JSON and expiry ordering checks.
- Added refresh token input regression test; five repeated offline test passes.

## 0.19.0
- Added authenticated profile display-name update API and validation tests.
- Preserved user-edited display name on subsequent external OIDC sign-ins.

## 0.20.0
- Approved new memory features and removed retrospective reminders. Added migration 005 and backend planning validators/tests.

## 0.24.0
- Added authenticated create/list time capsule API with one-year minimum, owner and duration checks, and hidden locked moment IDs.
- Added capsule tests. Database and device integration pending.

## 0.25.0
- Added authenticated DELETE /v1/me/capsules/:id to cancel an owned, still-locked time capsule.
- Cancellation requires UUID identifier, owner match, no prior cancellation, and future release date; missing/unavailable records use a non-disclosing response.
- Added three backend unit tests covering ownership/lock guard, malformed ID, and missing record.
- Verified 68 backend tests, Node syntax, project structure. PostgreSQL integration and Android compilation remain unverified.

## 0.28.0
Added authenticated MP4 ingestion with ffprobe duration validation, 25 MiB bound, private moment DB insert and cleanup on DB failure. See AUDIT_0.28.0.md.

## 0.29.0
Android `MomentoApiClient.uploadMoment` streams a local MP4 using fixed-length HTTP POST to the 0.28 server endpoint. Enforces 25 MiB, HTTPS, authorization, capture date and validates server UUID. No automatic retries: upload endpoint is not idempotent, and retry after an uncertain network response could create duplicates. Offline queue and upload state transitions are pending.

## 0.30.0
Added idempotent video uploads via existing upload_idempotency schema, stable Android request ID parameter, replay checks and regression tests.
