# MOMENTO 0.52.0 — random-only room reminders

- Removed all manually entered clock times, reminder counts and 1/3/6-hour buttons from the room UI.
- One per-room switch enables exactly five **scheduled** random local wall-clock slots per day, between 09:00 inclusive and 21:00 exclusive, with >=45-minute gaps.
- Deterministic room/date seed prevents reshuffling after a normal restart. AlarmManager schedules one next alarm per room; after delivery, the receiver advances the cursor. A delivered-count guard limits notifications to five per local day.
- Reboot, clock and timezone broadcasts replan the remaining slots. No past notifications are replayed.
- Android 12+ may deny exact-alarm permission. The scheduler then uses inexact `setAndAllowWhileIdle`; delivery may be late or outside the planned window. A delivery-time local-hour check suppresses out-of-window notifications.
- Android 13+ notification permission is requested from the UI. A denied permission prevents displaying notifications.
- **Limitations:** exact alarm permission settings screen is not automatically opened; per-room disable on delete/complete is not yet integrated with every room lifecycle path; reminders are enabled only for PUBLISHED local rooms; multi-timezone same-day scheduling is best effort; an OS-delayed alarm may be skipped, so five delivered notifications cannot be guaranteed. Android compile and device tests are unverified.
