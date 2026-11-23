#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
URL="https://raw.githubusercontent.com/gradle/gradle/v9.3.1/gradle/wrapper/gradle-wrapper.jar"
EXPECTED="b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13"
DEST="gradle/wrapper/gradle-wrapper.jar"
mkdir -p gradle/wrapper
if [[ -f "$DEST" ]] && printf '%s  %s\n' "$EXPECTED" "$DEST" | sha256sum -c --status; then
  echo 'Official Gradle Wrapper JAR already verified.'
  exit 0
fi
TMP="$(mktemp gradle/wrapper/.wrapper.XXXXXXXX)"
trap 'rm -f "$TMP"' EXIT
curl --fail --location --retry 3 --connect-timeout 20 --max-time 180 "$URL" -o "$TMP"
if ! printf '%s  %s\n' "$EXPECTED" "$TMP" | sha256sum -c -; then
  echo 'SECURITY ERROR: Gradle Wrapper JAR SHA-256 mismatch; refusing to use it.' >&2
  exit 1
fi
mv -f "$TMP" "$DEST"
echo 'Official Gradle Wrapper JAR verified and installed.'
