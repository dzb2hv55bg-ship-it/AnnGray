# MOMENTO 0.25.0 audit

Scope: server-side time capsule cancellation, not a completed Android feature.

- Endpoint: `DELETE /v1/me/capsules/:id`, authenticated via existing user middleware.
- SQL updates only capsules belonging to caller, still locked and not cancelled.
- Responses: 200 success, 422 malformed ID, 404 unavailable or already opened.
- No media is disclosed by this operation.
- Tests: 68 Node tests passed, `npm run check` passed, structural verifier passed.
- Limitations: no real PostgreSQL instance, Android Gradle build or device tests in this environment. UI integration, push reminders and media playback are still pending.
