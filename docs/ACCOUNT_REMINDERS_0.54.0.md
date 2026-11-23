# MOMENTO 0.54.0 — account-wide reminders and build handoff

## Verified changes
- Replaced per-room alarm scheduler with a **single account-wide** AlarmManager PendingIntent. Room preferences only choose eligible targets.
- Five stable pseudo-random times per local date, 09:00–20:59, >=45 min gap. Multiple rooms do not multiply alerts.
- An enabled room can be a draft, permitting first capture without a publication cycle. Completed/deleted rooms are removed by reconciliation.
- First partial day: skip earlier slots, never catch up. Late/Doze deliveries are suppressed outside 09:00–21:00. If Android delays a delivery, five delivered alerts are **not guaranteed**.
- Persisted pending epoch and delivery counter avoid duplicate processing across restarts; one account-wide alarm identifier.
- On boot, timezone change, time change, app update and exact-alarm permission change, receiver requests rescheduling. Logout clears local reminders.
- Removed old per-room periodic WorkManager reminder worker to prevent duplicate notifications.

## Actual checks run in this environment
- Kotlin compiler + JVM execution of RoomReminderRules on 10,000 dates: PASS.
- Python SQLite migration smoke test: PASS.
- Python source-structure guard: PASS (NOT an Android compiler).
- Node backend unit tests: 86/86 PASS.
- Android Gradle build, Android lint, Android unit tests and device tests: NOT RUN (SDK and Gradle Wrapper JAR unavailable).

## Required external action
Push source into an approved GitHub repository and manually run **Actions > Android APK build > Run workflow**. Attach the full failure log if it fails; do not assume the workflow is green. The workflow runs Android tests/lint, assembleDebug, and uploads APK only after successful build.

## Remaining limitations
- Four-tab UI and public subscription flow are not implemented in this patch; previous five-tab UI remains.
- Reminder target is the last enabled room; user-selectable quick switch in camera remains incomplete.
- SCHEDULE_EXACT_ALARM and POST_NOTIFICATIONS may be denied; delivery can be late.
- Account-scoped schedule uses local account identifier as a seed, not a cryptographically random secret.
- SharedPreferences is a pragmatic persisted cursor, not a transactional multi-process scheduler; test receiver concurrency on a real device.
- Source-only patch: cannot claim BUILD SUCCESSFUL or a debug APK.
