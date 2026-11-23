# MOMENTO 0.43.0 — Android CI build unblock

The previous Android CI workflow was guaranteed to fail because it required a missing Gradle Wrapper JAR. It now installs a pinned Gradle distribution (9.3.1) via `gradle/actions/setup-gradle@v4` and runs `gradle :app:assembleDebug` directly. This avoids an unverified wrapper JAR and makes the Android compilation gate executable in GitHub Actions.

**Important:** The CI build has not been run here. Gradle/Android SDK and network artifact resolution are unavailable in the current execution environment; Android dependencies may still fail to resolve or compile. A real build and device tests remain release blockers. The CI action and version combination should be validated on GitHub.
