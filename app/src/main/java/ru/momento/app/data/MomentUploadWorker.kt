package ru.momento.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.momento.app.network.MomentoApiClient
import ru.momento.app.network.MomentoApiException
import ru.momento.app.network.MomentoSessionVault
import ru.momento.app.network.MomentoSessionRefresh

/** Only uploads previously queued moments for the server-verified owner. */
class MomentUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val expectedOwner = inputData.getString(OWNER_KEY) ?: return@withContext Result.failure()
        val vault = MomentoSessionVault(applicationContext)
        if (vault.readVerifiedAccountId() != expectedOwner) return@withContext Result.failure()
        val database = LocalMomentDatabase(applicationContext, expectedOwner)
        val rooms = LocalSpaceDatabase(applicationContext, expectedOwner)
        try {
            var retryNeeded = false
            database.recoverInterruptedUploads()
            val client = MomentoApiClient()
            for (moment in database.queuedMoments()) {
                // The session may expire during a long queue. Refresh before EACH item,
                // and never send a moment under a different account or session.
                val activeSession = try {
                    MomentoSessionRefresh.refreshForOwner(vault, expectedOwner)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return@withContext if (e is MomentoApiException && e.statusCode in 400..499 && e.statusCode !in listOf(408, 429)) Result.failure() else Result.retry()
                } ?: return@withContext Result.failure()
                if (vault.readVerifiedAccountId() != expectedOwner ||
                    vault.read()?.accessToken != activeSession.accessToken) return@withContext Result.failure()
                val requestId = moment.uploadRequestId ?: continue
                database.updateUploadState(moment.id, LocalMomentDatabase.UPLOAD_UPLOADING)
                try {
                    val remoteId = client.uploadMoment(activeSession.accessToken, moment.file, moment.capturedAtMillis, requestId)
                    database.completeUpload(moment.id, requestId, remoteId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // 401 may mean token expiry mid-upload. Preserve the queue item,
                    // and let the bounded retry path attempt session refresh.
                    // Other 4xx (except 408/429) need user intervention.
                    val permanent = e is IllegalArgumentException ||
                        (e is MomentoApiException && e.statusCode in 400..499 &&
                            e.statusCode != 401 && e.statusCode != 408 && e.statusCode != 429)
                    database.updateUploadState(moment.id,
                        if (permanent) LocalMomentDatabase.UPLOAD_FAILED else LocalMomentDatabase.UPLOAD_QUEUED)
                    if (!permanent) retryNeeded = true
                }
            }
            // Drafts need explicit publication. Published vlogs also sync newly recorded,
            // explicitly queued clips via their existing durable room mapping.
            val pendingByRoom = database.roomSyncItems().groupBy({ it.second }, { it.first })
            val pendingRooms = rooms.getSpaces().filter {
                it.visibility == "PUBLIC" && (it.publicationRequested ||
                    (it.status == "PUBLISHED" && it.id in pendingByRoom))
            }
            for (requestedRoom in pendingRooms) {
                val room = rooms.roomById(requestedRoom.id) ?: continue
                val pendingIds = pendingByRoom[room.id].orEmpty().toSet()
                val links = rooms.momentsForRoom(room.id).filter {
                    room.publicationRequested || it.momentId in pendingIds
                }
                val moments = database.getMoments().associateBy { it.id }
                val clips = links.mapNotNull { moments[it.momentId] }
                if (room.status == "COMPLETED" || clips.isEmpty() || clips.size != links.size ||
                    clips.any { it.uploadState == LocalMomentDatabase.UPLOAD_FAILED }) {
                    rooms.clearPublicationRequest(room.id)
                    for (id in pendingIds) database.setRoomSyncPhase(id, room.id, "FAILED")
                    continue
                }
                val unuploaded = clips.filter { it.remoteMomentId == null }
                if (unuploaded.isNotEmpty()) {
                    for (clip in unuploaded) {
                        if (clip.uploadState == LocalMomentDatabase.UPLOAD_LOCAL_ONLY) database.queueUpload(clip.id)
                    }
                    retryNeeded = true
                    continue
                }
                val token = try {
                    MomentoSessionRefresh.refreshForOwner(vault, expectedOwner)?.accessToken
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val permanent = e is MomentoApiException && e.statusCode in 400..499 &&
                        e.statusCode !in listOf(408, 429)
                    return@withContext if (permanent) Result.failure() else Result.retry()
                } ?: return@withContext Result.failure()
                try {
                    val remoteRoom = room.remoteId ?: client.createSyncedRoom(
                        token, room.id, room.name, room.description, "PUBLIC"
                    ).also { remoteId ->
                        if (rooms.roomById(room.id) != null) rooms.saveRemoteId(room.id, remoteId)
                    }
                    for (clip in clips) {
                        if (vault.readVerifiedAccountId() != expectedOwner) return@withContext Result.failure()
                        val currentRoom = rooms.roomById(room.id) ?: break
                        if (currentRoom.status == "COMPLETED" ||
                            rooms.momentsForRoom(room.id).none { it.momentId == clip.id }) continue
                        client.attachUploadedMoment(token, remoteRoom, checkNotNull(clip.remoteMomentId))
                        database.setRoomSyncPhase(clip.id, room.id, "DONE")
                    }
                    if (vault.readVerifiedAccountId() != expectedOwner) return@withContext Result.failure()
                    if (rooms.roomById(room.id)?.publicationRequested == true) {
                        client.publishRoom(token, remoteRoom)
                        if (rooms.roomById(room.id) != null) rooms.markPublished(room.id)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (rooms.roomById(room.id) == null) continue
                    val permanent = e is MomentoApiException && e.statusCode in 400..499 &&
                        e.statusCode !in listOf(401, 408, 429)
                    if (permanent) {
                        rooms.clearPublicationRequest(room.id)
                        for (id in pendingIds) database.setRoomSyncPhase(id, room.id, "FAILED")
                    } else retryNeeded = true
                }
            }
            if (retryNeeded) Result.retry() else Result.success()
        } finally {
            rooms.close()
            database.close()
        }
    }


    companion object {
        private const val OWNER_KEY = "verified_owner_id"
        fun enqueue(context: Context, verifiedOwnerId: String) {
            val request = OneTimeWorkRequestBuilder<MomentUploadWorker>()
                .setInputData(workDataOf(OWNER_KEY to verifiedOwnerId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "momento_upload_$verifiedOwnerId", ExistingWorkPolicy.APPEND_OR_REPLACE, request
            )
        }
    }
}
