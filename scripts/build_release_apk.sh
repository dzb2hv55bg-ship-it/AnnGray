#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${MOMENTO_KEYSTORE:-}" || -z "${MOMENTO_KEY_ALIAS:-}" || -z "${MOMENTO_STORE_PASSWORD:-}" || -z "${MOMENTO_KEY_PASSWORD:-}" ]]; then
 echo 'Release signing variables missing. See BUILD_ANDROID.md; refusing to label an unsigned APK installable.' >&2; exit 2
fi
[[ -f "$MOMENTO_KEYSTORE" ]] || { echo 'Keystore file does not exist' >&2; exit 2; }
./gradlew :app:assembleRelease --no-daemon
APK=app/build/outputs/apk/release/app-release.apk
[[ -s "$APK" ]] || { echo 'Signed release APK missing' >&2; exit 1; }
echo "Release APK produced: $APK (verify signature with apksigner before distribution)"
