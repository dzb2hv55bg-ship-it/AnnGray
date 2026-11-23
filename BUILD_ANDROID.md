# MOMENTO Android build

## Gradle Wrapper
The build uses Gradle 9.3.1. `scripts/restore_gradle_wrapper.sh` verifies the official Wrapper JAR against its pinned SHA-256 and restores it from the Gradle release tag if needed. The Wrapper verifies the Gradle distribution against the checksum in `gradle-wrapper.properties`.

## Requirements
- JDK 17
- Android SDK command line tools and `platforms;android-37.0`, `build-tools;36.0.0`
- Network access to Google Maven, Maven Central, Gradle distribution and VK Maven

## Linux/macOS
```bash
export ANDROID_HOME="$HOME/Android/Sdk" # change to actual SDK path
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
bash scripts/setup_android_build.sh
./gradlew --version
bash scripts/build_debug_apk.sh
```
Result: `app/build/outputs/apk/debug/app-debug.apk` (debug signed by Android build tools). This path does **not** exist until successful build.

To build the APK directly and run Android unit tests:

```bash
./gradlew assembleDebug :app:testDebugUnitTest
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk
```

## Windows
Install JDK 17 and Android Studio SDK Platform API 37, Build Tools 36.0.0. Set `ANDROID_HOME` to the SDK path. Run `scripts/restore_gradle_wrapper.sh` in Git Bash (or download the official wrapper JAR and verify SHA-256 `b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13`). Then run `gradlew.bat clean assembleDebug`.

## GitHub Actions
Upload this **project root**, not the outer ZIP folder, to a GitHub repository. Set the repository variable `MOMENTO_API_URL` to the real HTTPS API base URL. Trigger the **Android APK build** workflow; download `MOMENTO-debug-apk` after a successful run. The workflow requires a configured endpoint for the debug artifact intended for live authentication testing.

## Build for live authentication testing
Set `MOMENTO_API_URL` in your shell to the deployed HTTPS API base URL, without the `/v1` route suffix. Then run:

```bash
./gradlew assembleDebug :app:testDebugUnitTest \
  -PmomentoRequireLiveAuth=true \
  "-PmomentoApiBaseUrl=$MOMENTO_API_URL"
```

This validates the form of the URL, not server reachability or delivery. Test receiving and confirming a code separately by email and SMS. Without the URL, a normal debug build can be used to preview the signed-out UI only; it cannot sign in or sync.

## Release signing
```bash
keytool -genkeypair -v -keystore momento-release.jks -alias momento -keyalg RSA -keysize 3072 -validity 10000
```
Store the keystore and passwords outside the repository. To sign, set `MOMENTO_KEYSTORE` (absolute keystore path), `MOMENTO_KEY_ALIAS`, `MOMENTO_STORE_PASSWORD`, `MOMENTO_KEY_PASSWORD`, then run `bash scripts/build_release_apk.sh`. Confirm with `$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose app/build/outputs/apk/release/app-release.apk`. Never commit credentials. Debug APK is for testing only.

## Limitations
- The API endpoint defaults to empty. First sign-in and server sync require `-PmomentoApiBaseUrl=https://YOUR_SERVER` when building. `https://api.momento.example` is only a placeholder.
- Google/Yandex/VK OAuth needs application registrations and real credentials.
- Real-device tests, video recording, camera permissions and sync require an APK and device.
- No guarantee of first-attempt compilation can be made without running the Android compiler.
