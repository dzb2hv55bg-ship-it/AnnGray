# MOMENTO 0.23.0 — Android video avatar preview

- Added `VideoAvatarSection` to profile; chooses only an existing local 2–5-second clip, loops video, and stops playback on release.
- Explicitly **session-only**: selection is not persisted, uploaded, or shown to other users. This avoids cross-account privacy leakage while server-side identity-scoped avatar storage is not implemented.
- Next: account-scoped avatar storage, secure media upload, lifecycle handling, device tests, and UI scrolling improvements.
- GitHub references inspected by repository lookup: https://github.com/androidx/media ; https://github.com/android/camera-samples ; https://github.com/android/compose-samples . No code was copied from these repositories.
- No Android APK or device test claimed.
