# MOMENTO 0.42.0: CI and release gates

Added `.github/workflows/quality.yml` with independent backend and Android jobs. Backend runs Node tests, syntax checks, SQLite migration regression, and structural checks. Android attempts a real debug APK build, uploading the artifact only if successful.

**Blocker:** the official Gradle Wrapper is absent. Android job intentionally fails until an official, checksum-verified wrapper is committed. Android compilation and device operation remain unverified. CI is not yet executed on GitHub.

## Remaining acceptance criteria
- [ ] Official Gradle Wrapper and verified dependencies
- [ ] Successful debug APK compilation and launch on device
- [ ] All provider logins and token rotation verified
- [ ] 2–5 second MP4 capture/playback, offline and online
- [ ] Cross-account privacy and eventual upload on network changes
- [ ] Rooms, invitations, room diary and room film integration
- [ ] Avatar, life calendar, stats and time capsule integration
- [ ] PostgreSQL, media storage and recovery integration
- [ ] Minor safety, moderation, privacy and legal review
- [ ] Monitoring, load tests, backup/restore and release signing
