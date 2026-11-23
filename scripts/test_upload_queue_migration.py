#!/usr/bin/env python3
"""SQLite compatibility regression for Android v1→v2 upload queue schema."""
import sqlite3
import uuid

conn = sqlite3.connect(':memory:')
conn.execute('''CREATE TABLE moments (
 id TEXT NOT NULL PRIMARY KEY, local_path TEXT NOT NULL UNIQUE,
 captured_at_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL CHECK(duration_ms BETWEEN 1 AND 5000),
 byte_size INTEGER NOT NULL CHECK(byte_size > 0), upload_state TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
 created_at_ms INTEGER NOT NULL)''')
local_id = str(uuid.uuid4())
conn.execute('INSERT INTO moments VALUES (?,?,?,?,?,?,?)', (local_id, '/private/video.mp4', 123, 3000, 4000, 'UPLOADING', 123))
conn.execute('ALTER TABLE moments ADD COLUMN upload_request_id TEXT')
conn.execute('ALTER TABLE moments ADD COLUMN remote_moment_id TEXT')
conn.execute('CREATE UNIQUE INDEX index_moments_request_id ON moments(upload_request_id)')
conn.execute("UPDATE moments SET upload_state = 'FAILED' WHERE upload_state = 'UPLOADING'")
row = conn.execute('SELECT local_path, duration_ms, upload_state FROM moments WHERE id=?', (local_id,)).fetchone()
assert row == ('/private/video.mp4', 3000, 'FAILED'), row
request_id = str(uuid.uuid4())
conn.execute('UPDATE moments SET upload_request_id=?, upload_state=? WHERE id=?', (request_id, 'QUEUED', local_id))
remote_id = str(uuid.uuid4())
conn.execute("UPDATE moments SET remote_moment_id=?, upload_state='UPLOADED_PRIVATE' WHERE id=? AND upload_request_id=?", (remote_id, local_id, request_id))
assert conn.execute('SELECT remote_moment_id FROM moments WHERE id=?', (local_id,)).fetchone()[0] == remote_id
other_id = str(uuid.uuid4())
conn.execute('INSERT INTO moments (id,local_path,captured_at_ms,duration_ms,byte_size,created_at_ms) VALUES (?,?,?,?,?,?)', (other_id,'/private/other.mp4',123,3000,4000,123))
try:
    conn.execute('UPDATE moments SET upload_request_id=? WHERE id=?', (request_id, other_id))
except sqlite3.IntegrityError:
    pass
else:
    raise AssertionError('Duplicate request ID accepted')
conn.execute("ALTER TABLE moments ADD COLUMN sync_room_id TEXT")
conn.execute("ALTER TABLE moments ADD COLUMN sync_phase TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
conn.execute("UPDATE moments SET sync_room_id=?,sync_phase='QUEUED' WHERE id=?",('room-uuid',local_id))
assert conn.execute('SELECT sync_room_id,sync_phase FROM moments WHERE id=?',(local_id,)).fetchone() == ('room-uuid','QUEUED')
print('SQLite v1→v3 migration regression passed')
