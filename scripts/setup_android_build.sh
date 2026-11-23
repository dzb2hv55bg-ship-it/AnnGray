#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v java >/dev/null || { echo 'Install JDK 17 first'; exit 2; }
if [[ ! -f gradle/wrapper/gradle-wrapper.jar ]]; then bash scripts/restore_gradle_wrapper.sh; fi
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  echo 'ANDROID_HOME or ANDROID_SDK_ROOT must point to an Android SDK installation.' >&2; exit 2
fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT}}"
if [[ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
else
  echo 'Android command-line tools missing: install from developer.android.com/studio#command-tools' >&2; exit 2
fi
