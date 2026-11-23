#!/usr/bin/env python3
"""Fail-fast checks for reproducible Android build prerequisites.
This does NOT compile the Android app.
"""
from pathlib import Path
import re, shutil, sys, tomllib
ROOT = Path(__file__).resolve().parents[1]
errors = []
def check(condition, message):
    if not condition: errors.append(message)

app = (ROOT / 'app/build.gradle.kts').read_text()
versions = tomllib.loads((ROOT / 'gradle/libs.versions.toml').read_text())
settings = (ROOT / 'settings.gradle.kts').read_text()
check('versionName = "0.54.3"' in app, 'Android versionName mismatch')
check('versionCode = 37' in app, 'Android versionCode mismatch')
check('compileSdk = 37' in app, 'compileSdk not pinned to expected 37')
check('targetSdk = 36' in app, 'targetSdk not pinned to expected 36')
check('google()' in settings and 'mavenCentral()' in settings, 'Required repositories absent')
check('android-application' in versions['plugins'], 'Android Gradle plugin absent')
check('kotlin-compose' in versions['plugins'], 'Compose compiler plugin absent')
check((ROOT/'app/src/main/AndroidManifest.xml').exists(), 'Android manifest absent')
check((ROOT/'backend/package.json').exists(), 'Backend manifest absent')
check((ROOT/'gradlew').exists() and (ROOT/'gradle/wrapper/gradle-wrapper.jar').exists(),
      'Gradle wrapper absent: use a trusted Gradle installation or generate and commit wrapper')
print('Android build prerequisites:')
print('  Java:', shutil.which('java') or 'MISSING')
print('  Gradle:', shutil.which('gradle') or 'MISSING')
print('  Android SDK:', 'FOUND' if __import__('os').environ.get('ANDROID_HOME') or __import__('os').environ.get('ANDROID_SDK_ROOT') else 'NOT CONFIGURED')
print('  Gradle wrapper:', 'PRESENT' if (ROOT/'gradlew').exists() else 'ABSENT')
for error in errors: print('FAIL:', error)
if errors:
    sys.exit(1)
print('PASS: static build prerequisites. Android compilation still required.')
