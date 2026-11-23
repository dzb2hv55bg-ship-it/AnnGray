package ru.momento.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/** Offline-first room index. Invite links are deliberately not fabricated before a server exists. */
data class LocalSpace(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val syncState: String,
    val description: String = "",
    val visibility: String = "PRIVATE",
    val status: String = "DRAFT",
    val topic: String = "Другое",
    val remoteId: String? = null,
    val publicationRequested: Boolean = false
)

data class RoomMomentLink(val momentId: String, val series: String, val position: Int)

class LocalSpaceDatabase(context: Context, accountId: String) : SQLiteOpenHelper(
    context.applicationContext,
    "spaces_${LocalMomentDatabase.databaseNameForAccount(accountId)}",
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE spaces (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 60),
                created_at_ms INTEGER NOT NULL,
                sync_state TEXT NOT NULL DEFAULT 'LOCAL_ONLY'
                    CHECK(sync_state IN ('LOCAL_ONLY', 'SYNC_PENDING', 'SYNCED', 'FAILED'))
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX index_spaces_created_at ON spaces(created_at_ms DESC)")
        addRoomColumns(db)
        addRoomMomentTable(db)
        addRemoteMapping(db)
        addPublishIntent(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addRoomColumns(db)
        if (oldVersion < 3) addRoomMomentTable(db)
        if (oldVersion < 4) addRemoteMapping(db)
        if (oldVersion < 5) addPublishIntent(db)
    }

    private fun addRoomColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE spaces ADD COLUMN description TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE spaces ADD COLUMN visibility TEXT NOT NULL DEFAULT 'PRIVATE'")
        db.execSQL("ALTER TABLE spaces ADD COLUMN status TEXT NOT NULL DEFAULT 'DRAFT'")
        db.execSQL("ALTER TABLE spaces ADD COLUMN topic TEXT NOT NULL DEFAULT 'Другое'")
    }

    private fun addRoomMomentTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS room_moments (
            room_id TEXT NOT NULL,
            moment_id TEXT NOT NULL UNIQUE,
            series TEXT NOT NULL DEFAULT 'День 1',
            position INTEGER NOT NULL,
            PRIMARY KEY(room_id, moment_id),
            FOREIGN KEY(room_id) REFERENCES spaces(id) ON DELETE CASCADE
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_room_moments_order ON room_moments(room_id, position)")
    }

    private fun addRemoteMapping(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE spaces ADD COLUMN remote_id TEXT")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS spaces_remote_id_idx ON spaces(remote_id)")
    }

    private fun addPublishIntent(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE spaces ADD COLUMN publication_requested INTEGER NOT NULL DEFAULT 0")
    }

    @Synchronized
    fun requestPublication(roomId: String) {
        val room = roomById(roomId) ?: error("Room not found")
        require(room.visibility == "PUBLIC" && room.status != "COMPLETED")
        require(momentsForRoom(roomId).isNotEmpty()) { "Cannot publish empty room" }
        check(writableDatabase.update("spaces",ContentValues().apply { put("publication_requested",1) },"id=?",arrayOf(roomId)) == 1)
    }

    @Synchronized
    fun markPublished(roomId: String) {
        check(writableDatabase.update("spaces",ContentValues().apply { put("status","PUBLISHED");put("sync_state",SYNC_SYNCED);put("publication_requested",0) },"id=? AND publication_requested=1",arrayOf(roomId)) == 1)
    }

    @Synchronized
    fun clearPublicationRequest(roomId: String) {
        writableDatabase.update("spaces", ContentValues().apply { put("publication_requested", 0) },
            "id = ?", arrayOf(roomId))
    }

    @Synchronized
    fun roomById(id: String): LocalSpace? = getSpaces().firstOrNull { it.id == id }

    @Synchronized
    fun saveRemoteId(roomId: String, remoteId: String) {
        require(UUID.fromString(remoteId).toString().equals(remoteId, true))
        val changed = writableDatabase.update("spaces", ContentValues().apply {
            put("remote_id", remoteId); put("sync_state", SYNC_SYNCED)
        }, "id = ? AND (remote_id IS NULL OR remote_id = ?)", arrayOf(roomId,remoteId))
        check(changed == 1) { "Room mapping changed or room deleted" }
    }

    /** Room ownership is account-scoped; moment IDs are checked by the caller's archive. */
    @Synchronized
    fun addMoment(roomId: String, momentId: String, series: String = "День 1") {
        require(series.isNotBlank() && series.length <= 80)
        writableDatabase.beginTransaction()
        try {
            val roomExists = readableDatabase.rawQuery("SELECT 1 FROM spaces WHERE id = ?", arrayOf(roomId)).use { it.moveToFirst() }
            require(roomExists) { "Room not found" }
            val next = readableDatabase.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM room_moments WHERE room_id = ?", arrayOf(roomId)).use {
                it.moveToFirst(); it.getInt(0)
            }
            writableDatabase.insertWithOnConflict("room_moments", null, ContentValues().apply {
                put("room_id", roomId); put("moment_id", momentId); put("series", series); put("position", next)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    @Synchronized
    fun momentsForRoom(roomId: String): List<RoomMomentLink> {
        val links = mutableListOf<RoomMomentLink>()
        readableDatabase.query("room_moments", arrayOf("moment_id", "series", "position"),
            "room_id = ?", arrayOf(roomId), null, null, "position ASC, moment_id ASC").use { cursor ->
            while (cursor.moveToNext()) links += RoomMomentLink(cursor.getString(0), cursor.getString(1), cursor.getInt(2))
        }
        return links
    }

    @Synchronized
    fun removeMoment(roomId: String, momentId: String) {
        writableDatabase.delete("room_moments", "room_id = ? AND moment_id = ?", arrayOf(roomId, momentId))
    }

    @Synchronized
    fun setSeries(roomId: String, momentId: String, series: String) {
        require(series.isNotBlank() && series.length <= 80)
        writableDatabase.update("room_moments", ContentValues().apply { put("series", series) },
            "room_id = ? AND moment_id = ?", arrayOf(roomId, momentId))
    }

    @Synchronized
    fun setRoomStatus(roomId: String, status: String) {
        require(status in setOf("DRAFT", "PUBLISHED", "COMPLETED"))
        if (status == "PUBLISHED") {
            val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM room_moments WHERE room_id = ?", arrayOf(roomId)).use {
                it.moveToFirst(); it.getInt(0)
            }
            require(count > 0) { "Cannot publish an empty room" }
        }
        writableDatabase.update("spaces", ContentValues().apply { put("status", status) }, "id = ?", arrayOf(roomId))
    }

    @Synchronized
    fun createSpace(name: String): LocalSpace = createRoom(name, "", "PRIVATE", "Другое")

    @Synchronized
    fun createRoom(name: String, description: String, visibility: String, topic: String): LocalSpace {
        val cleanName = name.trim()
        require(cleanName.length in 1..60) { "Room name must contain 1–60 characters" }
        require(visibility in setOf("PRIVATE", "PUBLIC", "LINK"))
        require(description.length <= 500)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("id", id)
            put("name", cleanName)
            put("created_at_ms", now)
            put("sync_state", SYNC_LOCAL_ONLY)
            put("description", description.trim())
            put("visibility", visibility)
            put("topic", topic)
            put("status", "DRAFT")
        }
        writableDatabase.insertOrThrow("spaces", null, values)
        return LocalSpace(id, cleanName, now, SYNC_LOCAL_ONLY, description.trim(), visibility, "DRAFT", topic)
    }

    @Synchronized
    fun getSpaces(): List<LocalSpace> {
        val result = mutableListOf<LocalSpace>()
        readableDatabase.query(
            "spaces", arrayOf("id", "name", "created_at_ms", "sync_state", "description", "visibility", "status", "topic", "remote_id", "publication_requested"),
            null, null, null, null, "created_at_ms DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += LocalSpace(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7), if (cursor.isNull(8)) null else cursor.getString(8), cursor.getInt(9) == 1)
            }
        }
        return result
    }

    companion object {
        const val SYNC_LOCAL_ONLY = "LOCAL_ONLY"
        private const val SYNC_SYNCED = "SYNCED"
        // Legacy unscoped rooms remain quarantined until ownership can be verified.
        private const val DATABASE_VERSION = 5
    }
}
