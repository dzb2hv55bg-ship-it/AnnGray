# MOMENTO 0.29.0 — Android upload client

Implemented `uploadMoment` in Android API client to stream app-private MP4 to authenticated server endpoint. This is API wiring, not a completed end-to-end sync pipeline.

**Known blockers:** Android SDK/Gradle build unavailable in this environment; no live PostgreSQL; no actual sign-in credentials; no upload idempotency key or retry queue; backend local disk storage is not production-ready; upload path lacks playback endpoint. Avoid automatic retries until idempotency is implemented.
