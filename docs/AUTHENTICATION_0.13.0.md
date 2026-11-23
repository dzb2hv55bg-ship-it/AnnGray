# MOMENTO authentication — 0.13.0

## Changes
- OTP challenge reservation is now serialized by a PostgreSQL advisory transaction lock per normalized contact. The cooldown/hourly checks and challenge insertion execute in one transaction.
- Provider upstream HTTP 408, 425, 429 and 5xx errors map to temporary provider unavailability.
- Android schedules refresh 60 seconds before MOMENTO access-token expiry while the process is alive.
- Android retries transient refresh failures every 15 seconds and clears a session only after a definitive 401 response.
- Android and backend version metadata: 0.13.0.

## Validation performed in this environment
- `npm test`: 28 passed, 0 failed.
- `npm run check`: passed.
- `python scripts/verify_project.py`: passed, including its nested backend test run.
- ZIP integrity: checked after packaging.

## Not verified
- Android Gradle compilation/APK/device tests: no Gradle executable, Gradle wrapper, Android SDK or emulator is available in this environment.
- PostgreSQL migrations and transaction/concurrency behavior: no PostgreSQL client/server or Docker is available here.
- Live Google/Yandex/VK authentication: app registrations and credentials are not available.
- Email/SMS delivery: no delivery providers or credentials are configured.
- VK protected key is currently supplied as an Android manifest placeholder by the official SDK integration. Because manifest values are extractable from an APK, treat this as a release blocker until the VK flow is reviewed and configured according to the current public-client/PKCE option and VK requirements.
