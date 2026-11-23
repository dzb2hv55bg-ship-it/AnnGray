# MOMENTO 0.27.0 — Avatar persistence API

New authenticated `PUT /v1/auth/me/avatar` accepts `{ "mediaAssetId": "UUID" }` or `{ "mediaAssetId": null }` to clear. The update is atomic and requires a READY, non-deleted, owned video asset with 2–5 seconds duration and a storage key. `GET /v1/auth/me` now includes `avatar_media_id`.

**Not yet implemented:** upload/storage and signed video playback URLs, Android client integration, moderation of avatar content, real PostgreSQL integration, Android APK build/device test. This endpoint cannot successfully set an avatar until a genuine uploaded READY asset exists. It must not be represented as complete video upload.
