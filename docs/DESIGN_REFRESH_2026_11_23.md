# MOMENTO — design refresh (2026-11-23)

## Implemented
- Shared `MomentoNavigationBar` across authenticated and guest flows: Camera / Rooms / Subscriptions / Profile.
- Shared section heading and status card (`MomentoSectionTitle`, `MomentoInfoCard`).
- Guest landing headings and server-unavailable state use shared components.
- No additional UI dependencies, remote images, animation libraries, or borrowed code.
- Existing blue light/dark Material 3 color schemes retained.

## Boundaries
- No change to video capture length (2–5 s), room access policy, offline uploads, or reminder scheduling.
- No fake public rooms when the backend is unavailable.
- No APK built or device UI tests run in this environment. Kotlin compile compatibility still needs Gradle validation.

## Verification
- Static delimiter balance checks on three changed Kotlin files.
- Python checks: `python -m pytest -q backend/test scripts/test_api_url.py scripts/test_live_moments_policy.py` (2 tests and 9 subtests passed in this environment).

## Next
- Compile and run Android UI smoke test with SDK 37 and a configured API or explicit offline-preview Gradle properties.
- Check layout on narrow screens, accessibility and dark theme.
