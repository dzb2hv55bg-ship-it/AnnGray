# MOMENTO — evidence-based integration review (2026-11-23)

Source: MOMENTO-design-refresh-2026-11-23.zip. This document describes inspected files, **not** a successful Android build.

## Existing build declarations
- Kotlin 2.4.21; AGP 9.1.1; Gradle Wrapper 9.3.1; compileSdk 37; targetSdk 36; minSdk 24; Compose BOM 2026.09.00.
- CameraX 1.7.0 (`camera-core`, `camera2`, `lifecycle`, `video`, `view`) and WorkManager 2.10.5 declared.
- No direct Media3, Retrofit, OkHttp, Coil, Hilt, Firebase, Robolectric, LeakCanary or detekt dependency in app Gradle catalog.
- `CameraScreen.kt` checks final output duration via MediaMetadataRetriever (2,000–5,000 ms). Check does not prove accurate capture on devices.
- `MomentUploadWorker.kt` refreshes account-bound sessions, retains requestId, and enqueues unique work with KEEP. This does not establish end-to-end exactly-once delivery.
- Existing GitHub Actions workflow includes Android unit/lint/build and emulator job; actual success not established here. Build requires HTTPS API configuration.

## Selected changes / gates (do not implement without version resolution and device tests)

| Priority | Problem | Source/component | License | Compatibility / dependency decision | Acceptance test |
|---|---|---|---|---|---|
| P0 | 2–5 second live camera output and finalization | android/camera-samples, CameraX Recorder | Apache-2.0; retain notices if code copied | Already has CameraX 1.7.0; no new dependency. Verify AGP/SDK via Gradle | Physical/virtual camera: 2, 5 seconds, early stop, permissions, interrupted capture, duration metadata and file cleanup |
| P0 | Upload retries without duplicates | android/architecture-samples, kotlinx.coroutines, existing WorkManager | Apache-2.0 (samples), Apache-2.0 (coroutines); preserve notices | WorkManager already present; no Retrofit/OkHttp added. Need API/server contract confirmation | Same idempotency key after timeout, server dedup, process death, logout mid-upload, concurrent worker, offline recovery |
| P1 | Sequential playback of moments | androidx/media Media3 ExoPlayer + PlayerView or Compose UI | Apache-2.0; preserve notices | Not present. Select Media3 version after resolving Maven metadata against actual SDK/AGP; add `media3-exoplayer` and `media3-ui` only | Sequential 2–5 s videos, disposal on navigation, error/empty stream, lifecycle pause, seek prohibition if intended |
| P1 | Build and reproducible CI logs | gradle/actions setup-gradle | Primarily MIT; bundled proprietary caching component, see DISTRIBUTION.md and NOTICE | Existing workflow uses setup-gradle; prefer basic cache provider if adopting newer action. No runtime dependency | `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` exits 0; artifact contains DEX |
| P1 | Emulator smoke checks | ReactiveCircus/android-emulator-runner | Apache-2.0 | Existing workflow has instrumented job; verify before changing action | Install APK, launch, guest navigation, permission denial, orientation and process recreation |
| P2 | UI architecture and state handling | android/nowinandroid, architecture-samples | Apache-2.0 | Patterns only; no copy of architecture wholesale | ViewModel state unit tests, empty/offline/loading states |
| P2 | Deterministic network transport | square/retrofit, square/okhttp | Apache-2.0 | Not needed until existing HTTP client fails concrete tests | MockWebServer request/response, timeouts, retries, auth expiry |
| P2 | Debug memory regressions | square/leakcanary | Apache-2.0 | Debug-only after build works | Repeated camera/player enter/exit and leak report |
| P2 | Static lint | detekt/detekt | Apache-2.0 | Separate Gradle plugin; defer until Gradle build passes | CI detekt task and zero baseline violations |

## Not selected
- Coil: only if real remote thumbnails require image loading. License Apache-2.0, verify specific artifact.
- Dagger/Hilt: no evidence that current DI needs a new framework. Apache-2.0.
- Firebase quickstarts: examples only; no Firebase integration required by MOMENTO. Individual examples' notices must be checked.
- Robolectric: consider only for Android-framework-dependent JVM tests; MIT license, check version and SDK support.
- kotlinx.serialization: only if current JSON parsing needs replacement; Apache-2.0.
- Android platform-samples / testing-samples / android-test: reference patterns, no new runtime libraries by default; verify individual license files before copying.

## Known blockers
- No Android SDK / emulator / Gradle distribution installed in the current execution environment.
- The `app/build.gradle.kts` explicitly refuses ordinary APK builds without a public HTTPS API URL; offline UI preview needs opt-in flags.
- Compatibility across Kotlin 2.4.21, AGP 9.1.1, Gradle 9.3.1, SDK 37 and Compose BOM 2026.09.00 is **unverified** until actual dependency resolution, compilation and emulator runs.
- The CI workflow's existence does not imply any successful run.

## Evidence and source URLs
- https://github.com/android/camera-samples
- https://github.com/androidx/media
- https://github.com/android/architecture-samples
- https://github.com/android/nowinandroid
- https://github.com/gradle/actions/blob/main/LICENSE
- https://github.com/ReactiveCircus/android-emulator-runner/blob/main/LICENSE
