# MOMENTO local work report — 2026-11-23

Work was performed **without GitHub** on the most recent `MOMENTO-guest-navigation-work-in-progress.zip` archive.

## Source changes

- Guest public-room catalog now has a text-search field with a 350 ms debounce. The existing public discovery endpoint receives the query; no mock rooms are injected.
- The catalog retains an offline state and distinguishes an absent API configuration from a failed connection.
- Camera action in guest UI is labelled honestly: authentication is required before recording and publishing. **Guest draft preservation has not been implemented.**
- The room player's back label refers to the rooms area when opened anonymously.
- Coroutine cancellation during search no longer incorrectly appears as a network failure.
- `scripts/build_local_apk.sh` supports building either an offline preview or a debug APK with a configured HTTPS API, without GitHub.
- `docs/LOCAL_BUILD_NO_GITHUB.md` documents Windows, Linux and macOS builds.

## Verification performed

- Backend Node.js test suite: **98 tests, 96 pass, 2 skipped, 0 fail**. Skipped cases require a PostgreSQL integration environment.
- Source consistency audit: passed.
- Room migration smoke test: passed.
- Shell script syntax check: passed.
- ZIP integrity verified after packaging.

## Not verified

- Android Gradle compile: NOT RUN (Android SDK unavailable in this environment).
- Android unit tests / Compose compile: NOT RUN (same reason).
- Device installation and end-to-end login, camera recording, sync, and video playback: NOT RUN.
- The source ZIP is **not an APK**. There is no new APK artifact from these changes.

## External requirements to ship a functional connected app

Android SDK + networked Gradle dependency resolution, a deployed HTTPS API, managed database, media storage, provider credentials for selected login methods, and on-device functional testing. These requirements are independent of GitHub hosting.
