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
        // Refresh only for the same server-verified account. A failed refresh
        // never authorizes an upload with stale credentials.
        val session = try {
            MomentoSessionRefresh.refreshForOwner(vault, expectedOwner)
        } catch (e: Exception) {
            val permanent = e is MomentoApiException && e.statusCode in 400..499 && e.statusCode != 408 && e.statusCode != 429
            return@withContext if (permanent) Result.failure() else Result.retry()
        } ?: return@withContext Result.failure()
        val database = LocalMomentDatabase(applicationContext, expectedOwner)
        val rooms = LocalSpaceDatabase(applicationContext, expectedOwner)
        try {
            database.recoverInterruptedUploads()
            val client = MomentoApiClient()
            for (moment in database.queuedMoments()) {
                // The session may expire during a long queue. Refresh before EACH item,
                // and never send a moment under a different account or session.
                val activeSession = try {
                    MomentoSessionRefresh.refreshForOwner(vault, expectedOwner)
                } catch (e: Exception) {
                    return@withContext if (e is MomentoApiException && e.statusCode in 400..499 && e.statusCode !in listOf(401,408,429)) Result.failure() else Result.retry()
                } ?: return@withContext Result.failure()
                if (vault.readVerifiedAccountId() != expectedOwner ||
                    vault.read()?.accessToken != activeSession.accessToken) return@withContext Result.failure()
                val requestId = moment.uploadRequestId ?: continue
                database.updateUploadState(moment.id, LocalMomentDatabase.UPLOAD_UPLOADING)
                try {
                    val remoteId = client.uploadMoment(activeSession.accessToken, moment.file, moment.capturedAtMillis, requestId)
                    database.completeUpload(moment.id, requestId, remoteId)
                } catch (e: Exception) {
                    // 401 may mean token expiry mid-upload. Preserve the queue item,
                    // and let the bounded retry path attempt session refresh.
                    // Other 4xx (except 408/429) need user intervention.
                    val permanent = e is IllegalArgumentException ||
                        (e is MomentoApiException && e.statusCode in 400..499 &&
                            e.statusCode != 401 && e.statusCode != 408 && e.statusCode != 429)
                    database.updateUploadState(moment.id,
                        if (permanent) LocalMomentDatabase.UPLOAD_FAILED else LocalMomentDatabase.UPLOAD_QUEUED)
                    if (!permanent) {
                        return@withContext Result.retry()
                    }
                }
            }
            // Durable room sync: server room key and remote moment IDs survive process death.
            // Only an explicit publication request can make a PUBLIC room discoverable.
            val pendingRooms = rooms.getSpaces().filter {
                it.visibility == "PUBLIC" && (it.publicationRequested || it.status == "PUBLISHED")
            }
            for (room in pendingRooms) {
                if (vault.readVerifiedAccountId() != expectedOwner) return@withContext Result.failure()
                // The room may have been deleted while the previous HTTP operation was in flight.
                if (rooms.roomById(room.id) == null) continue
                val token = try { MomentoSessionRefresh.refreshForOwner(vault, expectedOwner)?.accessToken }
                    catch (_: Exception) { null } ?: return@withContext Result.retry()
                try {
                    val remoteRoom = room.remoteId ?: client.createSyncedRoom(token,room.id,room.name,room.description,"PUBLIC")
                        .also { rooms.saveRemoteId(room.id,it) }
                    val links = rooms.momentsForRoom(room.id)
                    if (links.isEmpty()) continue
                    var complete = true
                    for (link in links) {
                        if (rooms.roomById(room.id) == null || vault.readVerifiedAccountId() != expectedOwner) return@withContext Result.failure()
                        val local = database.getMoments().firstOrNull { it.id == link.momentId }
                        if (local == null) { complete=false;break }
                        // A recorded moment is not automatically uploaded without explicit publication.
                        if (local.remoteMomentId == null) {
                            if (local.uploadState != LocalMomentDatabase.UPLOAD_QUEUED && local.uploadState != LocalMomentDatabase.UPLOAD_UPLOADING)
                                database.queueUpload(local.id)
                            complete=false;break
                        }
                        if (database.roomSyncItems().any { it.first == local.id && it.second == room.id }) {
                            client.attachUploadedMoment(token,remoteRoom,local.remoteMomentId)
                            database.setRoomSyncPhase(local.id,"DONE")
                        } else {
                            // Repeating attachment is safe: server uses ON CONFLICT DO NOTHING.
                            client.attachUploadedMoment(token,remoteRoom,local.remoteMomentId)
                        }
                    }
                    if (!complete) {
                        // A permanently failed local upload requires explicit user action;
                        // do not spin forever and do not discard the original video.
                        val blocked = links.any { link ->
                            database.getMoments().firstOrNull { it.id == link.momentId }
                                ?.uploadState == LocalMomentDatabase.UPLOAD_FAILED
                        }
                        return@withContext if (blocked) Result.failure() else Result.retry()
                    }
                    if (room.publicationRequested && rooms.roomById(room.id) != null) {
                        client.publishRoom(token,remoteRoom)
                        rooms.markPublished(room.id)
                    }
                } catch (e: Exception) {
                    val permanent = e is MomentoApiException && e.statusCode in 400..499 && e.statusCode !in listOf(401,408,429)
                    if (!permanent) return@withContext Result.retry()
                    return@withContext Result.failure()
                }
            }
            Result.success()
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
