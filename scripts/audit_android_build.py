#!/usr/bin/env python3
"""Honest, offline build prerequisites and source consistency audit."""
import os, shutil, hashlib
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
issues = []
for rel in ('settings.gradle.kts','build.gradle.kts','app/build.gradle.kts','gradle/libs.versions.toml','app/src/main/AndroidManifest.xml'):
    if not (ROOT/rel).is_file(): issues.append('Missing: '+rel)
wrapper = ROOT/'gradle/wrapper/gradle-wrapper.jar'
expected = 'b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13'
valid_wrapper = (ROOT/'gradlew').is_file() and wrapper.is_file() and hashlib.sha256(wrapper.read_bytes()).hexdigest() == expected
if not valid_wrapper and not shutil.which('gradle'):
    issues.append('No verified Gradle Wrapper JAR or system Gradle; run scripts/restore_gradle_wrapper.sh.')
sdk = os.getenv('ANDROID_HOME') or os.getenv('ANDROID_SDK_ROOT')
if not sdk or not (Path(sdk)/'platforms').exists(): issues.append('Android SDK platforms not configured.')
if not shutil.which('adb'): print('NOTE: adb unavailable; device tests require Android SDK platform-tools.')
manifest = (ROOT/'app/src/main/AndroidManifest.xml').read_text()
if 'android:exported="true"' not in manifest: issues.append('Launcher exported attribute missing.')
if '.orElse("")' not in (ROOT/'app/build.gradle.kts').read_text(): issues.append('API base URL must default to empty (offline mode).')
print('MOMENTO Android build preflight:')
for i in issues: print(' -',i)
print('STATUS:', 'BLOCKED' if issues else 'READY TO ATTEMPT BUILD')
raise SystemExit(2 if issues else 0)
