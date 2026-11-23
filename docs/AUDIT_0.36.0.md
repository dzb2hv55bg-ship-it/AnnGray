# MOMENTO 0.36.0 — single upload writer

- Archive button now only queues a moment and enqueues WorkManager.
- All network uploads run in the single unique WorkManager job per verified account.
- Interrupted UPLOADING records return to QUEUED with the same request UUID.
- No automatic room publication.
- Limitations: Android build/device and PostgreSQL integration still unverified; token refresh during worker execution is not implemented.
