# MOMENTO 0.47.0 — Archive sync-state refresh

## User-visible fix
Previously, WorkManager updated upload status in SQLite, but Compose state was only refreshed when the user queued an upload. As a result, the archive could continue showing "В очереди" after a successful background upload.

Added a lifecycle-scoped `LaunchedEffect(database, selectedTab)` that reads the account-specific SQLite archive every two seconds **only while the archive tab is selected** and updates Compose state only when records differ. The effect is cancelled when the tab changes. This is a polling bridge, not a production-grade reactive database observer.

## Limitations
- Android compilation and device tests are still not verified.
- A reactive Flow-based Room repository would be more efficient.
- Backend tests do not exercise this Compose behavior.
