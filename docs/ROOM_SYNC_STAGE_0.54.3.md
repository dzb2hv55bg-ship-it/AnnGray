# MOMENTO 0.54.3 — verified development stage (not a compiled APK)

## Implemented
- Server room creation accepts `clientRoomId` (account-scoped UUID). Migration `007_room_creation_keys.sql` stores stable local-to-server mapping. A repeated request returns the existing room, including after a lost HTTP response.
- Local SQLite room schema v5 adds `remote_id` and `publication_requested`; moment schema v3 adds `sync_room_id` and `sync_phase`. Older records remain, and unmapped rooms remain local.
- Explicit user action in a PUBLIC room queues publication. The worker creates/reuses a remote room, uploads each recorded moment using an existing idempotency key, attaches moments with server-side `ON CONFLICT DO NOTHING`, then calls publish. A successful upload alone never counts as publication. A PRIVATE room is not automatically published.
- The worker checks the verified owner and refreshes credentials; retry resumes using stored IDs. Original local video files are not removed on successful sync.
- New tests cover room-creation idempotency and a PostgreSQL A/B integration scenario (the PostgreSQL test is skipped locally and is configured in GitHub Actions with an isolated PostgreSQL service).
- Fixed a pre-existing CI blocker: `npm ci` required a missing package-lock.json. Workflows now use `npm install` pending a committed lockfile.

## Sync states
- Local moment upload: `LOCAL_ONLY` → `QUEUED` → `UPLOADING` → `UPLOADED_PRIVATE` (or `FAILED`).
- Room attachment: `LOCAL_ONLY` → `QUEUED` → `DONE`. `remote_moment_id` is persisted after upload; `remote_id` is persisted after room creation. Repeated attach and publish requests are safe to retry on the server.
- A public room is discoverable only after a successful explicit `publishRoom` call; the local `PUBLISHED` status is written only after server confirmation.
- A room's `publication_requested` flag is durable until the worker confirms publication.

## Tests actually run in this environment
- Node unit suite: 94 passed, 1 PostgreSQL integration skipped (95 total).
- Node syntax checks: passed.
- Python SQLite migration smoke tests: passed.
- Source structural checks and project verification: passed. These do not compile Android.

## Not verified or not finished
- Android Gradle compilation, Lint, emulator and APK: NOT RUN. Android SDK and verified wrapper JAR are unavailable locally. GitHub Actions has not been run in this session.
- PostgreSQL integration: NOT RUN locally; the CI job is prepared, not confirmed successful.
- Client-side full A→B journey: NOT VERIFIED. The remote subscription screen exists, but no two-device or end-to-end run has been performed.
- New moments recorded after a room is published still require explicit publication action; automatic follower push is not implemented. Subscription updates are available through API polling.
- The current upload protocol uses authenticated tokens and metadata checks, not cryptographic proof of camera origin. An adversarial modified client can still fabricate media. Do not claim absolute provenance.
- Background upload has a finite retry budget; permanent failures require manual intervention. A remote room deletion during sync causes server rejection, not silent re-creation under a new account.
- No hosted backend, domain, TLS, media storage, OIDC provider credentials, or push service is configured.

## External build
1. Push the project root to a GitHub repository after reviewing changes.
2. Run `Android APK build` in Actions. Its debug job restores the Gradle wrapper, runs tests/lint and assembles an APK. If it fails, use the uploaded failure log for code fixes.
3. Run `MOMENTO quality gates` to execute the PostgreSQL integration job against its disposable CI database.
4. Only after a green build download `MOMENTO-debug-apk` from Actions artifacts.

## Local backend
Requires Node 22+, PostgreSQL 17+, `psql`, and environment variables from `backend/.env.example`. Start a disposable PostgreSQL via `docker compose -f backend/docker-compose.yml up -d`; run `cd backend && npm install --no-audit --no-fund`; apply migrations in order `bash scripts/apply-test-migrations.sh` only with a disposable `MOMENTO_TEST_DATABASE_URL`, or use the same SQL files with your intended database URL. Configure `MOMENTO_MEDIA_DIR`, OIDC and the HTTPS API base URL separately; do not place credentials in source control.
