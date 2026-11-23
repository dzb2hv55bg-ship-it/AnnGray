# MOMENTO: open room and subscriptions implementation stage

## Implemented and checked

- Added migration `006_live_vlog_subscriptions.sql` with independent publication (`published_at`) and completion (`completed_at`) columns; room/author subscriptions and per-moment room updates.
- Added `backend/src/live-vlogs.js`: publish a room only if owned, public, active, and containing a READY moment; attach owned uploaded moments transactionally; follow/unfollow room or author; list subscriptions, updates, and access-scoped room details.
- Added protected `GET /v1/moments/:id/video`: bearer authentication, owner/member/published-public access, user-block checks; no public static video URLs.
- Public discovery now filters unpublished rooms; public timelines require publication or membership and check blocks.
- Android `MomentoApiClient.kt`: new API calls for subscriptions, author/room follow, publication, moment attachment, authenticated private video retrieval.
- Android `SubscriptionsScreen.kt`: actual server-backed subscriptions, updates, discovery, follow/unfollow, retry/error/empty states; room timeline with sequential video retrieval to private cache and VideoView.
- Navigation remains Camera, Rooms, Subscriptions, Profile. No gallery import added.
- `npm run check` and `npm test` passed locally: 92 tests total, 6 new tests for live vlog logic using mock database adapters. These are NOT end-to-end HTTP/PostgreSQL tests.

## Not yet finished / do not claim as ready

- Local camera room IDs are not yet mapped to server room IDs, so Android does NOT yet complete the A-to-B live recording-to-publication scenario. Upload worker uploads a video but does not attach it to a remote room. The publish and attach methods exist in the API client but are not wired into a completed UI flow.
- No PostgreSQL instance available: migration 006 and SQL access rules have NOT been executed against PostgreSQL. New tests mock DB queries and do not verify SQL execution.
- Backend provenance of recorded video is NOT cryptographically guaranteed. `ffprobe` validates codec and duration, but any caller with a valid token could supply matching video bytes. Attestation/session challenge is still needed; cannot promise absolute camera provenance.
- Public viewer uses authenticated download into app-private cache and VideoView; video pause/swipe and lifecycle behavior have NOT been tested on Android. Authenticated video cache cleanup at logout is not yet implemented.
- New room subscriptions and updates are pull-based; no push delivery service is connected.
- API base URL requires a real HTTPS host. No server has been deployed.
- Android SDK/Gradle wrapper JAR not installed locally; Android compilation, lint, UI tests and APK generation NOT performed. Existing GitHub Actions workflow is unexecuted here.

## API additions

- `POST /v1/spaces/:id/moments` JSON `{ "momentId": "uuid" }` (owner only; uploaded READY moment)
- `POST /v1/spaces/:id/publish` (owner only; public room with READY moment)
- `GET /v1/spaces/:id` (owner, member or published public)
- `GET /v1/spaces/:id/moments` (owner/member or published public)
- `PUT|DELETE /v1/spaces/:id/subscription`
- `PUT|DELETE /v1/authors/:id/subscription`
- `GET /v1/me/subscriptions`
- `GET /v1/me/subscription-updates`
- `GET /v1/moments/:id/video` (authenticated, access-checked)

## Server deployment prerequisites

1. Configure PostgreSQL and run SQL migrations `001` through `006` in numeric order, once each, in a managed migration process. **Back up before applying to an existing database.**
2. Set environment variables from `backend/.env.example`, including DATABASE_URL, PUBLIC_APP_URL, independent random secrets, and MOMENTO_MEDIA_DIR; install ffprobe/ffmpeg.
3. `cd backend && npm ci && npm run check && npm test && npm start`.
4. Configure HTTPS reverse proxy and persistent media storage, apply access and backup policies, and use a real auth provider. Never put server secrets into Android build files.
5. Set `MOMENTO_API_BASE_URL` through the Android build configuration as documented in root README and `app/build.gradle.kts`.

## Build

Workflow: `.github/workflows/android-build.yml`. For Android compilation, an external Android-capable runner is required. Do not claim BUILD SUCCESSFUL without a real workflow log.
