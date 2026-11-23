#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'kotlinc unavailable' >&2; exit 2; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
kotlinc app/src/main/java/ru/momento/app/data/RoomReminderRules.kt scripts/check_reminder_invariants.kt -include-runtime -d "$tmp/reminders.jar"
java -jar "$tmp/reminders.jar"
