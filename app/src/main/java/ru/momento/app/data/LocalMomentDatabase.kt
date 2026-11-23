package ru.momento.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.UUID
import java.security.MessageDigest

/** A small, durable local index for captured clips. Video bytes remain in app-private filesDir. */
data class LocalMoment(
    val id: String,
    val localPath: String,
    val capturedAtMillis: Long,
    val durationMillis: Long,
    val byteSize: Long,
    val uploadState: String,
    val uploadRequestId: String?,
    val remoteMomentId: String?
) {
    val file: File get() = File(localPath)
}

class LocalMomentDatabase(context: Context, private val accountId: String) : SQLiteOpenHelper(
    context.applicationContext,
    databaseNameForAccount(accountId),
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE moments (
                id TEXT NOT NULL PRIMARY KEY,
                local_path TEXT NOT NULL UNIQUE,
                captured_at_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL CHECK(duration_ms BETWEEN 2000 AND 5000),
                byte_size INTEGER NOT NULL CHECK(byte_size > 0),
                upload_state TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
                upload_request_id TEXT UNIQUE,
                remote_moment_id TEXT,
                created_at_ms INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX index_moments_captured_at ON moments(captured_at_ms DESC)")
        db.execSQL("CREATE INDEX index_moments_upload_state ON moments(upload_state)")
        addSyncColumns(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Keep migrations explicit. Never drop a user's local video index on upgrade.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE moments ADD COLUMN upload_request_id TEXT")
            db.execSQL("ALTER TABLE moments ADD COLUMN remote_moment_id TEXT")
            db.execSQL("CREATE UNIQUE INDEX index_moments_request_id ON moments(upload_request_id)")
            // A process can terminate mid-upload; the stable request ID survives.
            db.execSQL("UPDATE moments SET upload_state = 'FAILED' WHERE upload_state = 'UPLOADING'")
        }
        if (oldVersion < 3) addSyncColumns(db)
    }

    private fun addSyncColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE moments ADD COLUMN sync_room_id TEXT")
        db.execSQL("ALTER TABLE moments ADD COLUMN sync_phase TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
    }

    @Synchronized
    fun markForRoomSync(momentId: String, roomId: String) {
        val updated = writableDatabase.update("moments", ContentValues().apply {
            put("sync_room_id",roomId); put("sync_phase","QUEUED")
        }, "id=? AND (sync_room_id IS NULL OR sync_room_id=?)", arrayOf(momentId,roomId))
        check(updated == 1) { "Moment belongs to another room or was removed" }
    }

    @Synchronized
    fun roomSyncItems(): List<Pair<String,String>> {
        val result = mutableListOf<Pair<String,String>>()
        readableDatabase.rawQuery("SELECT id,sync_room_id FROM moments WHERE sync_room_id IS NOT NULL AND sync_phase IN ('QUEUED', 'ATTACHED') ORDER BY captured_at_ms ASC",null).use { c ->
            while(c.moveToNext()) result += c.getString(0) to c.getString(1)
        }
        return result
    }

    @Synchronized
    fun setRoomSyncPhase(momentId: String, roomId: String, phase: String) {
        require(phase in setOf("QUEUED","ATTACHED","DONE","FAILED"))
        writableDatabase.update("moments",ContentValues().apply { put("sync_phase",phase) },"id=? AND sync_room_id=?", arrayOf(momentId, roomId))
    }

    @Synchronized
    fun insertMoment(file: File, durationMillis: Long, capturedAtMillis: Long = System.currentTimeMillis()): LocalMoment {
        require(file.exists() && file.isFile && file.length() > 0L) { "Video file is missing or empty" }
        require(durationMillis in MIN_DURATION_MS..MAX_DURATION_MS) { "Moment duration must not exceed 5 seconds" }
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("local_path", file.absolutePath)
            put("captured_at_ms", capturedAtMillis)
            put("duration_ms", durationMillis)
            put("byte_size", file.length())
            put("upload_state", UPLOAD_LOCAL_ONLY)
            put("created_at_ms", System.currentTimeMillis())
        }
        writableDatabase.insertOrThrow("moments", null, values)
        return LocalMoment(
            id = values.getAsString("id"),
            localPath = file.absolutePath,
            capturedAtMillis = capturedAtMillis,
            durationMillis = durationMillis,
            byteSize = file.length(),
            uploadState = UPLOAD_LOCAL_ONLY,
            uploadRequestId = null,
            remoteMomentId = null
        )
    }

    /** Recover finalized CameraX files after a crash between Finalize and SQLite insertion.
     * Only account-private camera output names are considered; no gallery imports.
     * The account database is the only destination, preventing cross-account recovery.
     */
    @Synchronized
    fun recoverUnindexedCaptures(context: Context): Int {
        val directory = File(context.filesDir, "moments_${databaseNameForAccount(accountId).removeSuffix(".db")}")
        if (!directory.isDirectory) return 0
        val indexed = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT local_path FROM moments", null).use { cursor ->
            while (cursor.moveToNext()) indexed += cursor.getString(0)
        }
        var recovered = 0
        directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("moment_[0-9]{8}_[0-9]{6}_[0-9]{3}\\.mp4")) && it.absolutePath !in indexed }
            ?.forEach { file ->
                if (file.length() == 0L) return@forEach
                val retriever = android.media.MediaMetadataRetriever()
                val duration = try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } catch (_: Exception) { null } finally { runCatching { retriever.release() } }
                if (duration != null && duration in MIN_DURATION_MS..MAX_DURATION_MS) {
                    // Never remove the file if indexing fails; it can be retried on next launch.
                    if (runCatching { insertMoment(file, duration, file.lastModified()) }.isSuccess) recovered++
                }
            }
        return recovered
    }

    @Synchronized
    fun getMoments(): List<LocalMoment> {
        val result = mutableListOf<LocalMoment>()
        val staleIds = mutableListOf<String>()
        readableDatabase.query(
            "moments",
            arrayOf("id", "local_path", "captured_at_ms", "duration_ms", "byte_size", "upload_state", "upload_request_id", "remote_moment_id"),
            null, null, null, null, "captured_at_ms DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(1)
                // Remove stale index entries if the user or OS removed a local file.
                if (!File(path).isFile) {
                    staleIds += cursor.getString(0)
                    continue
                }
                result += LocalMoment(
                    id = cursor.getString(0),
                    localPath = path,
                    capturedAtMillis = cursor.getLong(2),
                    durationMillis = cursor.getLong(3),
                    byteSize = cursor.getLong(4),
                    uploadState = cursor.getString(5),
                    uploadRequestId = if (cursor.isNull(6)) null else cursor.getString(6),
                    remoteMomentId = if (cursor.isNull(7)) null else cursor.getString(7)
                )
            }
        }
        if (staleIds.isNotEmpty()) {
            writableDatabase.beginTransaction()
            try {
                staleIds.forEach { writableDatabase.delete("moments", "id = ?", arrayOf(it)) }
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
        }
        return result
    }

    fun hasPendingUploads(): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM moments WHERE upload_state IN ('QUEUED', 'UPLOADING') AND upload_request_id IS NOT NULL LIMIT 1",
        null
    ).use { it.moveToFirst() }

    /** WorkManager may only process records explicitly queued by the owner. */
    @Synchronized
    fun queuedMoments(): List<LocalMoment> = getMoments().filter {
        it.uploadState == UPLOAD_QUEUED && it.uploadRequestId != null
    }

    @Synchronized
    fun updateUploadState(momentId: String, state: String) {
        require(state in setOf(UPLOAD_LOCAL_ONLY, UPLOAD_QUEUED, UPLOAD_UPLOADING, UPLOAD_UPLOADED_PRIVATE, UPLOAD_FAILED))
        writableDatabase.update("moments", ContentValues().apply { put("upload_state", state) }, "id = ?", arrayOf(momentId))
    }

    /** Enqueue once: every subsequent attempt must reuse the same UUID. */
    @Synchronized
    fun queueUpload(momentId: String): String {
        val existing = readableDatabase.query(
            "moments", arrayOf("upload_request_id", "upload_state"), "id = ?", arrayOf(momentId), null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw IllegalArgumentException("Unknown moment")
            val requestId = if (cursor.isNull(0)) null else cursor.getString(0)
            val state = cursor.getString(1)
            require(state != UPLOAD_UPLOADED_PRIVATE) { "Moment already uploaded" }
            requestId
        }
        val stableId = existing ?: UUID.randomUUID().toString()
        val updated = writableDatabase.update("moments", ContentValues().apply {
            put("upload_request_id", stableId)
            put("upload_state", UPLOAD_QUEUED)
        }, "id = ? AND upload_state != ?", arrayOf(momentId, UPLOAD_UPLOADED_PRIVATE))
        check(updated == 1) { "Moment cannot be queued" }
        return stableId
    }

    /** On success, store server identity and state in a single durable SQLite write. */
    @Synchronized
    fun completeUpload(momentId: String, requestId: String, remoteId: String) {
        require(UUID.fromString(requestId).toString() == requestId)
        require(UUID.fromString(remoteId).toString() == remoteId)
        val updated = writableDatabase.update("moments", ContentValues().apply {
            put("remote_moment_id", remoteId)
            put("upload_state", UPLOAD_UPLOADED_PRIVATE)
        }, "id = ? AND upload_request_id = ? AND upload_state != ?",
            arrayOf(momentId, requestId, UPLOAD_UPLOADED_PRIVATE))
        check(updated == 1) { "Upload completion mismatch" }
    }

    /** Recover interrupted uploads without creating a new idempotency key. */
    @Synchronized
    fun recoverInterruptedUploads() {
        writableDatabase.execSQL("UPDATE moments SET upload_state = 'QUEUED' WHERE upload_state = 'UPLOADING' AND upload_request_id IS NOT NULL")
    }

    @Synchronized
    fun deleteMoment(momentId: String, deleteFile: Boolean): Boolean {
        val path = readableDatabase.query(
            "moments", arrayOf("local_path"), "id = ?", arrayOf(momentId), null, null, null
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        if (path == null) return false
        val removed = writableDatabase.delete("moments", "id = ?", arrayOf(momentId)) > 0
        if (removed && deleteFile) File(path).delete()
        return removed
    }

    companion object {
        const val MIN_DURATION_MS = 2_000L
        const val MAX_DURATION_MS = 5_000L
        const val UPLOAD_LOCAL_ONLY = "LOCAL_ONLY"
        const val UPLOAD_QUEUED = "QUEUED"
        const val UPLOAD_UPLOADING = "UPLOADING"
        const val UPLOAD_UPLOADED_PRIVATE = "UPLOADED_PRIVATE"
        const val UPLOAD_FAILED = "FAILED"
        // Do not open the legacy global database: its records have no reliable owner.
        // Those files remain on disk for a future explicit recovery/migration flow.
        fun databaseNameForAccount(accountId: String): String {
            require(UUID.fromString(accountId).toString().equals(accountId, ignoreCase = true)) {
                "Account identity must be a UUID"
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(accountId.lowercase().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "momento_local_${digest}.db"
        }
        private const val DATABASE_VERSION = 3
    }
}
