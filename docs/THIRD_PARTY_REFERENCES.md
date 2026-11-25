# Third-party development references

MOMENTO is an independent implementation. These projects are engineering references; their product identity, branding, screens and private APIs are not copied.

| Project | Intended use | License / reuse note |
|---|---|---|
| [google/jetpack-camera-app](https://github.com/google/jetpack-camera-app) | CameraX recording lifecycle, preview/review, quality capability handling and camera tests | Apache-2.0; retain required notices if source is copied |
| [android/nowinandroid](https://github.com/android/nowinandroid) | Offline-first repository pattern, local source of truth, WorkManager synchronization and testing structure | Apache-2.0; retain required notices if source is copied |
| [android/camera-samples](https://github.com/android/camera-samples) | CameraX Recorder video recording reference and camera testing examples | Check license at pinned revision before copying source |
| [android/architecture-components-samples](https://github.com/android/architecture-components-samples) | Room, lifecycle, persistence and WorkManager examples | Check the license file at the exact revision before copying individual files |
| [Google Credential Manager](https://developer.android.com/identity/sign-in/credential-manager) | Google sign-in and credential handling | AndroidX/Google libraries; follow the exact dependency licenses and Google branding rules |
| [Yandex ID Android SDK](https://yandex.ru/dev/id/doc/en/mobileauthsdk/android/3.1.3/sdk-android) | Yandex account authorization | Yandex SDK; review SDK license and provider terms for the selected release |
| [VK ID Android SDK](https://github.com/VKCOM/vkid-android-sdk) | VK ID OAuth 2.1 authorization | MIT; retain license and verify provider SDK configuration/security guidance |
| [tus/tus-android-client](https://github.com/tus/tus-android-client) | Possible future resumable upload fallback if real network tests justify it | MIT; not included in the current app |

Projects with GPL/AGPL terms or no clearly identified license are not used as source-code dependencies. Setlog's private implementation and undocumented APIs are not accessed. MOMENTO uses the high-level room-and-short-moment product idea and implements its own code and backend contract.

## Source review rule
Before copying any code from a repository:
1. Pin the exact commit or release.
2. Read the license file at that revision.
3. Record the origin and copyright notice.
4. Check whether dependencies bring additional license obligations.
5. Add required notices to the app's third-party notices.

## MOMENTO 0.55

| Reference | Use | License |
|---|---|---|
| [AndroidX Media 1.8.0](https://github.com/androidx/media/tree/1.8.0) and [Transformer](https://developer.android.com/media/media3/transformer) | MP4 export from a chronological composition. The Android module uses the same Media3 version for Transformer and ExoPlayer. | [Apache-2.0](https://github.com/androidx/media/blob/1.8.0/LICENSE) |
| [Android secure file sharing](https://developer.android.com/training/secure-file-sharing) | FileProvider grants read access to the selected copy via the Android Sharesheet. | Android platform guidance |
| [Android emulator runner](https://github.com/ReactiveCircus/android-emulator-runner/tree/v2) | API 35 instrumentation in the offline Android CI job. | [MIT](https://github.com/ReactiveCircus/android-emulator-runner/blob/v2/LICENSE) |
