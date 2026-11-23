#!/usr/bin/env python3
"""SQLite smoke test for the room schema migration, independent of Android SDK."""
import sqlite3
from pathlib import Path
source = (Path(__file__).resolve().parents[1] / 'app/src/main/java/ru/momento/app/data/LocalSpaceDatabase.kt').read_text()
assert 'DATABASE_VERSION = 5' in source
assert 'if (oldVersion < 4) addRemoteMapping(db)' in source
assert 'if (oldVersion < 5) addPublishIntent(db)' in source
assert 'if (oldVersion < 3) addRoomMomentTable(db)' in source
assert 'if (oldVersion < 2) addRoomColumns(db)' in source
assert 'require(visibility in setOf("PRIVATE", "PUBLIC", "LINK"))' in source
with sqlite3.connect(':memory:') as db:
    db.execute("CREATE TABLE spaces (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 60), created_at_ms INTEGER NOT NULL, sync_state TEXT NOT NULL DEFAULT 'LOCAL_ONLY')")
    db.execute("INSERT INTO spaces VALUES ('legacy','Старая комната',100,'LOCAL_ONLY')")
    for name, definition in [('description', "TEXT NOT NULL DEFAULT ''"), ('visibility', "TEXT NOT NULL DEFAULT 'PRIVATE'"), ('status', "TEXT NOT NULL DEFAULT 'DRAFT'"), ('topic', "TEXT NOT NULL DEFAULT 'Другое'")]:
        db.execute(f'ALTER TABLE spaces ADD COLUMN {name} {definition}')
    db.execute("ALTER TABLE spaces ADD COLUMN remote_id TEXT")
    db.execute("CREATE UNIQUE INDEX spaces_remote_id_idx ON spaces(remote_id)")
    db.execute("ALTER TABLE spaces ADD COLUMN publication_requested INTEGER NOT NULL DEFAULT 0")
    row = db.execute('SELECT name, description, visibility, status, topic FROM spaces WHERE id=?', ('legacy',)).fetchone()
    assert row == ('Старая комната', '', 'PRIVATE', 'DRAFT', 'Другое'), row
    db.execute("INSERT INTO spaces (id,name,created_at_ms,sync_state,description,visibility,status,topic) VALUES ('new','Поездка',200,'LOCAL_ONLY','Воспоминания','PUBLIC','DRAFT','Путешествие')")
    assert db.execute('SELECT COUNT(*) FROM spaces').fetchone()[0] == 2
    db.execute("UPDATE spaces SET remote_id='server-uuid',publication_requested=1 WHERE id='new'")
    assert db.execute("SELECT remote_id,publication_requested FROM spaces WHERE id='new'").fetchone() == ('server-uuid',1)
    db.execute("""CREATE TABLE room_moments (room_id TEXT NOT NULL, moment_id TEXT NOT NULL UNIQUE,
        series TEXT NOT NULL DEFAULT 'День 1', position INTEGER NOT NULL,
        PRIMARY KEY(room_id, moment_id), FOREIGN KEY(room_id) REFERENCES spaces(id) ON DELETE CASCADE)""")
    db.execute("CREATE INDEX index_room_moments_order ON room_moments(room_id, position)")
    db.execute("INSERT INTO room_moments (room_id, moment_id, position) VALUES ('legacy', 'moment-1', 0)")
    db.execute("INSERT INTO room_moments (room_id, moment_id, series, position) VALUES ('legacy', 'moment-2', 'День 2', 1)")
    assert db.execute("SELECT moment_id, series FROM room_moments ORDER BY position").fetchall() == [
        ('moment-1', 'День 1'), ('moment-2', 'День 2')]
    db.execute("UPDATE room_moments SET position = 2 WHERE moment_id = 'moment-1'")
    assert db.execute("SELECT moment_id FROM room_moments ORDER BY position").fetchall()[0][0] == 'moment-2'
    db.execute("DELETE FROM room_moments WHERE moment_id = 'moment-1'")
    assert db.execute("SELECT COUNT(*) FROM room_moments").fetchone()[0] == 1
print('Room migration smoke test OK: legacy rows preserved, ordered moments and series supported.')
