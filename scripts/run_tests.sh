#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
(cd backend && npm test && npm run check)
python3 scripts/check_android_sources.py
python3 scripts/test_upload_queue_migration.py
python3 scripts/verify_project.py
if [[ -f gradle/wrapper/gradle-wrapper.jar && -n "${ANDROID_HOME:-}" ]]; then
 ./gradlew :app:testDebugUnitTest --no-daemon
 if [[ "${RUN_INSTRUMENTED_TESTS:-0}" == 1 ]]; then ./gradlew :app:connectedDebugAndroidTest --no-daemon; fi
else echo 'Android tests SKIPPED: SDK or Wrapper JAR unavailable'; fi

python3 scripts/test_rooms_schema.py
