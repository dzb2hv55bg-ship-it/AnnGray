# MOMENTO 0.45.0 — source references and build gates

This release adds a source-provenance regression check and wires it into CI. The check verifies the curated third-party reference list is present, uses unique HTTPS URLs without embedded credentials, and documents license/pinning obligations. It does **not** establish that any remote source is safe, licensed for copying, or currently available; those checks require reviewing the exact revision.

## References reviewed for architecture

- [Google Jetpack Camera App](https://github.com/google/jetpack-camera-app): video capture lifecycle, time limits and camera validation. **Reference only; no code copied.**
- [Android Now in Android](https://github.com/android/nowinandroid/blob/main/docs/ArchitectureLearningJourney.md): local data source of truth and WorkManager-based sync. **Reference only; no code copied.**
- [Android Camera Samples](https://github.com/android/camera-samples): CameraX Recorder video sample. **Reference only; no code copied.**

## Release blockers

1. Android APK compilation is not verified (Gradle, SDK and remote dependencies must resolve).
2. OAuth credentials, running PostgreSQL and object storage are not configured for production.
3. End-to-end camera, offline sync, rooms, media playback and child-safety flows require device/integration tests.
4. Room diary, film assembly, avatar, time capsule, calendar and statistics require product-complete implementation.

Do not infer release readiness from passing Node unit tests or static checks.
