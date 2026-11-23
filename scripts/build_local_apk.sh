#!/usr/bin/env bash
# Build on a local computer; GitHub, GitHub Actions and Git credentials are NOT required.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f gradle/wrapper/gradle-wrapper.jar ]]; then
  echo 'Missing Gradle Wrapper JAR. Run: bash scripts/restore_gradle_wrapper.sh' >&2
  exit 2
fi
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo 'Android SDK is not configured. Set ANDROID_HOME to your SDK installation.' >&2
  exit 2
fi

if [[ -n "${MOMENTO_API_URL:-}" ]]; then
  python3 scripts/check_api_url.py "$MOMENTO_API_URL"
  echo 'Building debug APK using the supplied HTTPS API URL.'
  ./gradlew :app:testDebugUnitTest :app:assembleDebug \
    '-PmomentoRequireLiveAuth=true' "-PmomentoApiBaseUrl=$MOMENTO_API_URL" --no-daemon
  output=dist/MOMENTO-live-api-debug.apk
else
  echo 'Building OFFLINE UI PREVIEW only. Account sign-in, sync and publications will not work.'
  ./gradlew :app:testDebugUnitTest :app:assembleDebug \
    '-PmomentoAllowOfflinePreview=true' '-PmomentoRequireLiveAuth=false' --no-daemon
  output=dist/MOMENTO-offline-preview-debug.apk
fi
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk "$output"
python3 - "$output" <<'PYAPK'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as f:
    assert f.testzip() is None, 'Corrupted APK ZIP entries'
    assert 'AndroidManifest.xml' in f.namelist(), 'Missing AndroidManifest.xml'
    assert any(n.startswith('classes') and n.endswith('.dex') for n in f.namelist()), 'Missing DEX'
print('APK archive validated:', sys.argv[1])
PYAPK
