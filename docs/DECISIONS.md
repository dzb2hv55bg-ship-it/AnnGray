# Architecture decisions (initial)

- Android first, shared backend contract for future iOS.
- Native Kotlin + Jetpack Compose + CameraX. No React Native/Flutter in this implementation.
- Maximum video duration is 5 seconds, enforced in client and backend.
- Private-by-default moments; room access is granted explicitly.
- One source media asset can be shared with multiple rooms without file duplication.
- PostgreSQL stores metadata; private S3-compatible object storage stores media.
- Short-lived signed upload/playback URLs; never embed storage credentials in the app.
- API owns authorization; client-side checks are UX only.
- MVP excludes stranger DMs, infinite feed, complex recommender, heavy editor, livestreaming and commerce.

## Not yet selected / must be confirmed before production
- Backend hosting/provider and Russian-region deployment.
- Object storage provider and exact tariff/egress model.
- Authentication method and SMS/email provider.
- Legal age/consent policy and public-room launch rules.
- Media profile (720p vs 1080p, bitrate, audio defaults) after device tests.

## Camera MVP implementation (2026-11-20)
- CameraX VideoCapture/Recorder is the recording path; no custom codec pipeline.
- Camera permission is requested only when the user opens the recording screen.
- Initial recording profile requests HD and falls back through CameraX quality selection.
- A client-side timer stops capture at five seconds; the saved clip is written into app-private internal storage under `filesDir/moments`.
- Initial capture is silent (no microphone permission requested yet). Audio is a deliberate follow-up decision, not silently requested at onboarding.
- The archive currently scans app-private storage for saved MP4 files on launch; a Room index is deferred until the basic capture flow is device-verified.
- This is an implementation milestone, not yet device-verified. Device-specific capture duration, orientation, playback and process interruption still require tests.
