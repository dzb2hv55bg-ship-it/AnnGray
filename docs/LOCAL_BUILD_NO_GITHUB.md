# MOMENTO: local Android APK builds without GitHub

These instructions build an APK on your **own computer**. They do not require a GitHub account or GitHub Actions. A working Android SDK and Gradle dependency download are required.

## Modes

* **Offline UI preview**: permits guest UI and browsing local screens. No configured API means the app cannot perform registration, login, cloud sync, or public-vlog browsing.
* **Live API debug**: uses a real public HTTPS MOMENTO API URL to enable server requests. This does not by itself establish that the backend, external OAuth providers, email/SMS delivery, storage or moderation are working.

## Windows with Android Studio

1. Extract the project folder and open `MOMENTO-android` in Android Studio.
2. Install the SDK Platform for **Android API 37**, Build Tools **36.0.0**, a suitable JDK **17**, and make sure Gradle can download dependencies.
3. Set `ANDROID_HOME` to the Android SDK folder. In Android Studio you may also configure the SDK through the IDE.
4. For an **offline preview**, from the Terminal in the project root run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PmomentoAllowOfflinePreview=true -PmomentoRequireLiveAuth=false
```

5. For a build connected to a real backend (replace `https://YOUR_REAL_API_HOST` with an actually deployed HTTPS endpoint):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PmomentoRequireLiveAuth=true -PmomentoApiBaseUrl=https://YOUR_REAL_API_HOST
```

Output: `app/build/outputs/apk/debug/app-debug.apk` after Gradle finishes successfully. **No APK is produced by these commands if compilation fails.**

## Linux/macOS (or Windows Git Bash)

Install Android SDK, JDK 17 and set `ANDROID_HOME`. Run one command from the project root:

```bash
bash scripts/build_local_apk.sh
```

The script creates `dist/MOMENTO-offline-preview-debug.apk` after a successful build and unit tests.

To use a real configured server:

```bash
MOMENTO_API_URL=https://YOUR_REAL_API_HOST bash scripts/build_local_apk.sh
```

That creates `dist/MOMENTO-live-api-debug.apk` after a successful build. APK zip format and DEX are checked. The script does not claim device functionality without an actual device or emulator test.

## Signing and release

These are **debug** APKs, not signed production releases. Google/Yandex/VK logins require developer credentials and provider setup; email/SMS and video uploads require deployment of the backend and storage. Never upload passwords, signing keys or `.env` files to a public repository.

## Latest guest UI adjustments

Guest room search queries use `/v1/discovery/spaces` with a short debounce; the server must be reachable. The guest camera button explains that login is required **before** recording; no unimplemented guest-draft flow is promised. The public room back label returns to rooms rather than subscriptions.
