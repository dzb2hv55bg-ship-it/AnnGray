#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew clean :app:testDebugUnitTest :app:assembleDebug --no-daemon
APK=app/build/outputs/apk/debug/app-debug.apk
[[ -s "$APK" ]] || { echo "APK not produced: $APK" >&2; exit 1; }
python3 - "$APK" <<'PY2'
import sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    assert z.testzip() is None
    assert 'AndroidManifest.xml' in z.namelist()
    assert any(n.startswith('classes') and n.endswith('.dex') for n in z.namelist())
print('APK ZIP and DEX verified:',sys.argv[1])
PY2
