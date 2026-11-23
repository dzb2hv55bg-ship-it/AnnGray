# MOMENTO Android — development build

This repository is the first implementation scaffold, not a finished or installable release.

## Current contents
- Kotlin + Jetpack Compose Android app shell.
- Five core destinations: Home, Archive, Record, Rooms, Profile.
- Focused MVP scope document.
- Initial PostgreSQL schema for users, rooms, memberships, media, moments, audience grants, comments, reactions, blocks, reports and moderation.
- Five-channel MOMENTO authentication: Google Credential Manager, Yandex ID Android SDK, official VK ID Android SDK, email OTP and phone OTP; provider apps and delivery vendors still need configuration.
- Initial authenticated Node.js API foundation for server-side room creation/listing, invite issuance/redemption and member-only room timelines (see `backend/README.md`).
- Domain model with a hard 5-second duration invariant.
- CameraX preview and video recording screen; requests camera permission contextually.
- Recording automatically stops after five seconds and writes MP4 output to app-private storage.
- A durable local SQLite index stores clip metadata and restores the archive after app restart.
- Local room creation and a persistent “My rooms” list are implemented in a separate SQLite database.
- Added an authenticated Android HTTP client for server room operations and MOMENTO authentication; API requests require a MOMENTO session and HTTPS base URL.
- The camera checks the measured MP4 duration and rejects files longer than five seconds.

## Current implementation caveats
- Audio is not recorded in this first camera pass; microphone permission is not requested.
- Local metadata currently uses Android SQLite directly; Room migration is deferred until a reproducible Gradle build is available.
- Authentication source integration is present, but production activation is not complete: Yandex/Google/VK app credentials, deployed backend, PostgreSQL migrations, SMTP/SMS vendor configuration and real-device/provider sandbox verification remain outstanding.
- Google, Yandex and VK sign-in buttons are connected to their Android SDKs. Email/phone OTP screens are connected to the backend. The app stores MOMENTO tokens encrypted with Android Keystore and refreshes on startup.
- Backend supports provider identity provisioning, OTP sign-in, MOMENTO refresh/logout, one-time Google nonce validation, and explicit account linking. Auto-merging accounts by matching email is not allowed.
- Profile UI supports linking all five sign-in channels to the current MOMENTO account; email/phone require OTP and social providers require fresh provider authorization.
- Upload states are persisted, but there is no upload worker or deployed production backend yet.
- The Android UI still uses local-only rooms; the new API client is a transport layer and is not wired into screens yet. The backend source now contains server-side room/invite/timeline endpoints, but it is not deployed or connected to the app; the UI correctly continues to communicate local-only status.
- Camera behavior has not yet been verified on physical Android devices or by a successful Gradle build in this environment.

## Build environment
The development container used to prepare this scaffold has JDK 21 but does **not** have Android SDK, Gradle, or an Android emulator installed. Therefore no APK has been built and no device tests have been run here. Open the project in a current Android Studio with Android SDK API 37 and a Gradle version compatible with the configured Android Gradle Plugin, then sync/build. Re-check plugin/library compatibility in that environment before treating this dependency set as production-ready.

## Important next work
- Add microphone permission and optional audio recording; verify the user experience when microphone access is denied.
- Add a persistent upload queue and WorkManager only after the backend upload contract is deployed.
- Implement server-backed room creation, invite links/codes, membership and the chronological shared room timeline.
- Deploy and harden the backend service, complete account/age onboarding and connect the Android client; then add private object storage.
- Add tests for duration validation, process death, offline recovery, authorization and duplicate uploads.

See `docs/IMPLEMENTATION_LOG.md` for what is implemented versus pending, and `docs/THIRD_PARTY_REFERENCES.md` for GitHub reference projects and license rules.


Текущая версия Android-каркаса: 0.16.0. Backend API foundation: 0.16.0. См. `docs/IMPLEMENTATION_LOG.md` для журнала изменений и ограничений проверки.


Authentication architecture: see `docs/AUTHENTICATION_DESIGN.md`. Migrations `002_auth_channels.sql`, `003_auth_session_hardening.sql` and `004_auth_provider_nonce.sql` must be applied after `001_initial_schema.sql` in order on a fresh or properly migrated database.


### 0.13.0 — provider sign-in and OTP adapters

Added MOMENTO-owned short-lived access tokens, opaque refresh-token rotation, server-side session checks, logout/revocation, current-user endpoint, and refresh replay-family revocation. Migration `003_auth_session_hardening.sql` adds rotation lineage. This is the session foundation only: the configured upstream OIDC bridge still supports one issuer, and provider-specific Yandex/Google/VK plus email/phone delivery integrations remain outstanding.
