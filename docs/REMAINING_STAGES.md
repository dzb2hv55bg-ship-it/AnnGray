# MOMENTO: Remaining delivery stages (as of 0.41.0)

1. **Build** — add trusted Gradle wrapper, pin compatible JDK/Gradle/AGP, resolve dependencies, compile debug APK. Gate: clean build in CI.
2. **Identity** — test Google, Yandex, VK, email and SMS flows, token refresh, sign-out and offline owner binding. Gate: automated auth integration tests.
3. **Capture/archive** — validate real 2–5s MP4 recordings, playback, offline storage, crash recovery. Gate: device tests.
4. **Sync** — test WorkManager with network loss, token expiry, process death, account switching and idempotency. Gate: no duplicates or cross-account upload.
5. **Rooms** — complete permissions, invitations, open/private rooms, joint video diary and assembled room film. Gate: multi-user integration tests.
6. **Memories** — implement and test live 2–5s avatar, selectable week/month/all-time calendar, stats, >=1-year capsule. Gate: user acceptance tests.
7. **Infrastructure/safety** — PostgreSQL, Russian object storage, backups, moderation, children protection, Russian legal/privacy review. Gate: security and recovery tests.
8. **Release** — end-to-end tests, accessibility/performance, crash monitoring, signing, store assets and rollout. Gate: release candidate on real devices.

Progress is not measured by file count or passing server-only tests.
