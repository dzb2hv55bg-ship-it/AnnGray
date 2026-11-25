package ru.momento.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.momento.app.data.LocalMomentDatabase
import android.content.Context
import java.io.File
import java.util.UUID

/** Instrumented SQLite behavior, not a source-text inspection. */
@RunWith(AndroidJUnit4::class)
class MomentQueueRecoveryTest {
    @Test fun interruptedUploadKeepsIdempotencyKeyAndLocalFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = UUID.randomUUID().toString()
        val file = File(context.filesDir, "queue_test_${UUID.randomUUID()}.mp4")
        file.writeBytes(byteArrayOf(1, 2, 3))
        try {
            LocalMomentDatabase(context, owner).use { db ->
                val moment = db.insertMoment(file, 3000)
                val first = db.queueUpload(moment.id)
                db.updateUploadState(moment.id, LocalMomentDatabase.UPLOAD_UPLOADING)
                db.recoverInterruptedUploads()
                val pending = db.queuedMoments().single()
                assertEquals(first, pending.uploadRequestId)
                assertEquals(LocalMomentDatabase.UPLOAD_QUEUED, pending.uploadState)
                assertTrue(file.exists())
            }
            // Reopening SQLite simulates process restart: no new request key is generated.
            LocalMomentDatabase(context, owner).use { db ->
                val queued = db.queuedMoments().single()
                assertEquals(queued.uploadRequestId, db.queueUpload(queued.id))
                assertTrue(queued.file.exists())
            }
        } finally { file.delete(); context.deleteDatabase(LocalMomentDatabase.databaseNameForAccount(owner)) }
    }

    @Test fun sharedRoomDestinationSurvivesReopenAndDoesNotCrossAccounts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val room = UUID.randomUUID().toString()
        val file = File(context.filesDir, "room_queue_test_${UUID.randomUUID()}.mp4")
        file.writeBytes(byteArrayOf(1, 2, 3))
        try {
            val momentId = LocalMomentDatabase(context, owner).use { db ->
                db.insertMoment(file, 3000).id.also { db.queueSharedRoomMoment(it, room) }
            }
            LocalMomentDatabase(context, owner).use { db ->
                assertEquals(listOf(momentId to room), db.sharedRoomSyncItems())
                val requestId = requireNotNull(db.queuedMoments().single().uploadRequestId)
                db.completeUpload(momentId, requestId, UUID.randomUUID().toString())
                assertEquals(listOf(momentId to room), db.sharedRoomSyncItems())
                db.setRoomSyncPhase(momentId, "DONE")
                assertTrue(db.sharedRoomSyncItems().isEmpty())
                assertTrue(file.exists())
            }
            LocalMomentDatabase(context, other).use { db -> assertTrue(db.sharedRoomSyncItems().isEmpty()) }
        } finally {
            file.delete()
            context.deleteDatabase(LocalMomentDatabase.databaseNameForAccount(owner))
            context.deleteDatabase(LocalMomentDatabase.databaseNameForAccount(other))
        }
    }

    @Test fun differentAccountCannotSeeQueuedMoments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val file = File(context.filesDir, "owner_test_${UUID.randomUUID()}.mp4")
        file.writeBytes(byteArrayOf(1))
        try {
            LocalMomentDatabase(context, a).use { db -> db.queueUpload(db.insertMoment(file, 3000).id) }
            LocalMomentDatabase(context, b).use { db -> assertTrue(db.queuedMoments().isEmpty()) }
        } finally {
            file.delete()
            context.deleteDatabase(LocalMomentDatabase.databaseNameForAccount(a))
            context.deleteDatabase(LocalMomentDatabase.databaseNameForAccount(b))
        }
    }
}
