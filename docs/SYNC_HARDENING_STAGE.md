# MOMENTO — sync hardening, 2026-11-22

## Changes verified in source

- Existing four-section navigation retained. No new product screens or import paths.
- Recording into an already published PUBLIC room now durably marks the new local moment for room sync, queues the upload and schedules WorkManager. The recording remains on device even if queuing fails.
- Worker now processes both rooms awaiting first publication and already published public rooms. For the latter it attaches newly uploaded moments without re-publishing the room. The first publication still requires explicit user action.
- Stable upload_request_id survives process restarts; server upload_idempotency is keyed by account and request ID. The PostgreSQL integration suite now covers a lost-response replay with identical bytes and rejects replay with different bytes.
- CI PostgreSQL job explicitly installs psql, runs migrations, captures logs as an artifact and fails on test failure via pipefail.

## Durable sync stages

LOCAL_ONLY -> QUEUED -> UPLOADING -> UPLOADED_PRIVATE (server media accepted) -> room attachment (room_updates for published public rooms) -> DONE. The room mapping is persisted in account-scoped SQLite; upload idempotency uses stable UUID; attachment is conflict-safe. Local media is not deleted on upload completion.

## Remaining risks (not verified)

- PostgreSQL integration tests require an actual disposable PostgreSQL instance; this environment has no psql/postgres or Docker.
- Android build, UI behavior and WorkManager recovery need real Gradle/SDK execution.
- The local `UPLOADED_PRIVATE` state is an internal upload stage; it is not proof that the video has become publicly visible. UI status consistency needs on-device verification.
- Changing/removing a previously published local moment does not yet guarantee matching server-side deletion; avoid representing it as complete.
- Server does not cryptographically prove camera provenance; synthetic integration media must never be confused with actual captured media.
- Existing `backend/package-lock.json` is absent; npm install was not possible offline. Do not invent a lockfile.

## CI

Run `.github/workflows/quality.yml` for PostgreSQL integration and `.github/workflows/android-build.yml` for Android compile/test/lint. A successful workflow has not been observed.
