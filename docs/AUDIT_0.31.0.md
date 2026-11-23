# MOMENTO 0.31.0 — persistent upload identity

- Android SQLite schema v2 adds a unique persistent upload request UUID and remote moment UUID.
- v1→v2 migration preserves user videos and local archive entries.
- queueUpload() reuses the same request UUID on retries; completeUpload() atomically stores server ID and upload state.
- recoverInterruptedUploads() marks interrupted requests as FAILED, not successful; they can be explicitly retried using the same UUID.
- No background upload scheduling or automatic retry is enabled yet. A queue item is NOT proof that a video has reached the server.
- Android Gradle compilation, PostgreSQL integration and device tests remain unavailable in this environment.
