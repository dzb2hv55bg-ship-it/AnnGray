# MOMENTO 0.53.0 — Reminder lifecycle

## Implemented in source
- Exact-alarm settings deep link for Android 12+, with inexact AlarmManager fallback and user-facing delay warning.
- Android 13+ notification permission request remains separate from exact-alarm permission.
- Boot, clock, timezone, package replacement and exact-alarm permission change trigger alarm rescheduling.
- Room status reconciliation removes alarms for non-PUBLISHED, deleted or missing rooms on room database reload.
- Explicit disableAll helper cancels alarms during logout.
- Reject stale-day alarm deliveries; never send reminders outside 09:00–21:00 local time.
- Existing five deterministic pseudo-random daily slots per room, 45+ minutes apart, retained.

## Limits and security
- Android exact-alarm permission is special app access, not a runtime permission; settings screen may be declined. AlarmManager delivery is not guaranteed under power-management restrictions.
- Room status changes from code paths other than the UI must call `RoomAlarmScheduler.disable` immediately, or `reconcile` on next room refresh. The current UI does not expose room completion/deletion actions.
- Five *planned* reminders is not a guarantee of five delivered notifications; past slots are not backfilled.
- Local room reminders are not yet account-scoped in SharedPreferences. Logout cancels them; account identity changes outside the logout flow need separate handling.
- Android compilation, emulator tests, GitHub Actions run and APK availability are not verified.
- Open rooms, remote subscriptions and server-side provenance attestation are not complete. Client-provided metadata/signatures alone cannot prove a video originated from the camera.

## Build and installation
Upload the `MOMENTO-android` directory contents to a GitHub repository root. Under Actions choose `Android APK build` > Run workflow. On success download `MOMENTO-debug-apk` from the run's Artifacts. Extract and install `app-debug.apk` on Android, allowing installation from that file manager. If the build fails, download `MOMENTO-gradle-failure-log` and share the Gradle log.

## Manual smoke test
Check notification permission denial, exact alarm permission denial, reboot, timezone changes, offline capture, 2–5 second recording, auto-stop, room assignment, room switch-off, duplicate prevention, dark theme, player gestures, and logout cancellation. Use `adb logcat -d -v time > momento-logcat.txt` after reproducing a crash; redact tokens and private content before sharing.
