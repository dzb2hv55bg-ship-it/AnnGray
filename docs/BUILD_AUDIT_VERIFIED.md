# MOMENTO — verified build audit (2026-11-22)

Input: `MOMENTO-android-scaffold-0.47.0.zip`.

## Confirmed
- Native Android Kotlin/Jetpack Compose, single `:app` module, Gradle Kotlin DSL.
- `compileSdk=37`, `targetSdk=36`, `minSdk=24`, Java 17, version catalog.
- AGP 9.1.1, Kotlin Compose plugin 2.4.21 are declared; **their resolution and compatibility are not confirmed**.
- Camera, WorkManager, Credentials, Yandex and VK ID libraries are declared.
- Backend Node.js tests can run separately from Android.
- Default backend URL `https://api.momento.example` is a placeholder, not a deployed API.

## Blocking environment findings
- Android SDK and Gradle are not installed in this execution environment.
- No Gradle Wrapper binary included in input.
- Official Gradle download failed DNS resolution; unable to install missing tooling.
- No emulator/physical Android device available here.
- Consequently no `assembleDebug` execution, APK, or device verification can be claimed.

## Build on a configured machine
1. Install Android Studio, Android SDK Platform 37 and SDK Build Tools, JDK 17.
2. Open the `MOMENTO-android` folder in Android Studio and sync Gradle.
3. Install a Gradle version supported by the selected AGP; generate the official wrapper (`gradle wrapper`) after resolving versions, and commit all wrapper files.
4. Set the real backend URL and your own OAuth IDs through Gradle properties/environment variables; do not commit secrets.
5. Run `./gradlew clean :app:assembleDebug --stacktrace`.
6. Resolve any dependency/compilation errors reported by Gradle before using the APK.
7. Once built, install `app/build/outputs/apk/debug/app-debug.apk` using `adb install -r` and test with a configured backend.

## Release signing
Generate your own keystore with `keytool -genkeypair -v -keystore momento-release.jks -alias momento -keyalg RSA -keysize 3072 -validity 10000`, store it privately, configure signing in Android Studio > Build > Generate Signed Bundle / APK, select APK, and sign the release variant. Never commit the keystore/passwords.

## Not verified
Kotlin compilation, manifest merge, resource merge, APK signing, app startup, UI, camera, OAuth, database migrations on device, offline/online sync, production security, and legal compliance.
