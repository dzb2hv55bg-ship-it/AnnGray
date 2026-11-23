package ru.momento.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.momento.app.data.LocalMomentDatabase
import ru.momento.app.data.LocalSpaceDatabase
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
                assertTrue(db.hasPendingUploads())
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

    @Test fun permanentFailureRequiresExplicitRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = UUID.randomUUID().toString()
        val file = File.createTempFile("failed-upload-", ".mp4", context.cacheDir)
        file.writeBytes(byteArrayOf(1))
        try {
            LocalMomentDatabase(context, owner).use { db ->
                val moment = db.insertMoment(file, 3000)
                val requestId = db.queueUpload(moment.id)
                db.updateUploadState(moment.id, LocalMomentDatabase.UPLOAD_FAILED)
                db.recoverInterruptedUploads()
                assertFalse(db.hasPendingUploads())
                assertTrue(db.queuedMoments().isEmpty())
                assertTrue(file.exists())
                assertEquals(requestId, db.queueUpload(moment.id))
            }
        } finally {
            file.delete()
            context.deleteDatabase(LocalMomentDatabase.databaseNameForAccount(owner))
        }
    }

    @Test fun blockedPublicationCanBeRequestedAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = UUID.randomUUID().toString()
        try {
            LocalSpaceDatabase(context, owner).use { db ->
                val room = db.createRoom("Test room", "", "PUBLIC", "Other")
                val clipId = UUID.randomUUID().toString()
                db.addMoment(room.id, clipId)
                db.requestPublication(room.id)
                db.clearPublicationRequest(room.id)
                assertFalse(db.roomById(room.id)!!.publicationRequested)
                assertEquals(listOf(clipId), db.momentsForRoom(room.id).map { it.momentId })
                db.requestPublication(room.id)
                assertTrue(db.roomById(room.id)!!.publicationRequested)
            }
        } finally {
            context.deleteDatabase("spaces_${LocalMomentDatabase.databaseNameForAccount(owner)}")
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
