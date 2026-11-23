# MOMENTO 0.41.0: Android build readiness audit

## Delivered
- Version 0.41.0 in Android and backend, Android versionCode 22.
- `scripts/build_preflight.py` checks version metadata, required build files, repositories, and presence of a Gradle wrapper.
- `docs/REMAINING_STAGES.md` records acceptance gates for the eight remaining phases.

## Blocking issue found
The project archive has **no Gradle wrapper** (`gradlew` and wrapper JAR are absent). This is an actual reproducibility gap. Do not invent or download a Gradle wrapper JAR from unverified sources. Generate a wrapper with a trusted Gradle installation and commit the wrapper artifacts; then compile Android using a verified JDK/SDK.

## Not yet verified
Android compile, APK, device, PostgreSQL, and live OAuth providers. Source-only checks cannot establish those results.
