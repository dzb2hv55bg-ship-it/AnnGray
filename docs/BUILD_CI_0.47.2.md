# MOMENTO CI 0.47.2 — verification status

The workflow was **not run** on GitHub and Android compilation has **not been verified**. Do not advertise the ZIP as an APK.

Changes: verified atomic Wrapper recovery; a single Android debug build workflow with Node 22, JDK 17, SDK 37, test runner, full Gradle log and APK artifact; optional manual emulator job; optional release job requiring all four signing secrets; isolated the old quality workflow to backend checks only. Added an Android instrumentation package smoke test.

Official checksums: https://gradle.org/release-checksums/ . Gradle 9.3.1 wrapper JAR SHA256 b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13; distribution SHA256 b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06.

## Run

Push the extracted `MOMENTO-android` directory contents to the repository root, open Actions > Android APK build > Run workflow. After a successful run, open Artifacts > MOMENTO-debug-apk. A manual run also attempts emulator and signed release (release skips if secrets absent). Push triggers debug automatically. Download and install the debug APK on an Android phone. The APK is not present until a successful workflow run.

For release, create a keystore with `keytool -genkeypair -v -keystore momento-release.jks -alias momento -keyalg RSA -keysize 3072 -validity 10000`, store its base64 representation in ANDROID_KEYSTORE_BASE64 and its passwords/alias in the other three GitHub Actions secrets. Do not commit the keystore.

Server: `momentoApiBaseUrl` is a Gradle property, default is the non-functional `.example` placeholder. Without a real HTTPS backend, online sign-in and synchronization will not work. Google, Yandex and VK OAuth require registered client IDs/secrets.

Manual QA: install; launch; log in; record 2–5 second clip; archive playback; offline recording; reconnect and upload; open/closed rooms; invites; calendar; capsules; account logout; permissions; dark/light theme; tablet/landscape; rotation; background process death. These are NOT yet verified on a device.
