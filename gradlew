#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$ROOT/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "Missing Gradle Wrapper JAR. Run: bash scripts/restore_gradle_wrapper.sh" >&2
  exit 2
fi
exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
