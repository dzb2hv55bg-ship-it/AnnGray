# MOMENTO 0.30.0 — upload idempotency

- Each upload must supply stable UUID header `X-Momento-Request-Id`; Android caller must persist the UUID per local moment and reuse it on retries.
- PostgreSQL `upload_idempotency` table from migration 001 is used to serialize concurrent attempts per user and request ID.
- Replays with identical file checksum return original moment, status 200; a changed file yields 409.
- Server still buffers up to 25 MiB and writes to local storage. This is not production object storage.
- Remaining: Android persistent queue, PostgreSQL integration, Android compilation, end-to-end tests, better crash recovery and cleanup.
