#!/usr/bin/env python3
"""Offline structural guards; not a replacement for Android compilation."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
ui = root / 'app/src/main/java/ru/momento/app/ui'
room = (ui/'RoomMomentsScreen.kt').read_text()
camera = (ui/'CameraScreen.kt').read_text()
app = (ui/'MomentoApp.kt').read_text()
db = (root/'app/src/main/java/ru/momento/app/data/LocalSpaceDatabase.kt').read_text()
scheduler = (root/'app/src/main/java/ru/momento/app/data/RoomAlarmScheduler.kt').read_text()
assert 'showPicker' not in room and 'onAdd:' not in room
assert 'onMove:' not in room and 'fun moveMoment(' not in db
assert 'onSaved(output, duration)' in camera
assert 'LocalMomentDatabase.readCaptureDuration(output)' in camera
assert 'latestRecording.value?.stop()' in camera
assert 'onReminder' not in room and 'RoomAlarmScheduler.setEnabled' in app
assert 'fun migrateLegacy(' in scheduler and 'RoomAlarmScheduler.setAccount' in app
assert 'Cannot publish an empty room' in db
print('Live moment policy checks passed (structural, not runtime).')
