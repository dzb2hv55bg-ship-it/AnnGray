package ru.momento.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import ru.momento.app.network.MomentoApiClient
import ru.momento.app.network.MomentoApiException
import ru.momento.app.network.MomentoSessionRefresh
import ru.momento.app.network.MomentoSessionVault
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class SecretRoomNotifications(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString(ACCOUNT) ?: return Result.failure()
        val vault = MomentoSessionVault(applicationContext)
        if (vault.readVerifiedAccountId() != owner) return Result.success()
        val store = settings(applicationContext, owner)
        if (store.all.isEmpty()) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()
        try {
            val token = MomentoSessionRefresh.refreshForOwner(vault, owner)?.accessToken ?: return Result.success()
            val api = MomentoApiClient()
            val joined = api.listSpaces(token).filter { it.kind == "SECRET" }.mapTo(hashSetOf()) { it.id }
            for ((id, marker) in store.all) {
                if (id !in joined) {
                    store.edit().remove(id).apply()
                    continue
                }
                val recent = try {
                    api.getSpaceTimeline(token, id, limit = 20).items
                } catch (failure: MomentoApiException) {
                    if (failure.statusCode !in setOf(403, 404)) throw failure
                    store.edit().remove(id).apply()
                    emptyList()
                }
                val newest = recent.firstOrNull() ?: continue
                if (vault.readVerifiedAccountId() != owner || !store.contains(id)) return Result.success()
                if (newest.id == marker) continue
                store.edit().putString(id, newest.id).apply()
                if (recent.takeWhile { it.id != marker }.any { it.ownerId != owner }) {
                    val manager = applicationContext.getSystemService(NotificationManager::class.java)
                    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                        NotificationChannel(CHANNEL, "Секретные комнаты", NotificationManager.IMPORTANCE_DEFAULT)
                    )
                    manager.notify(id.hashCode(), NotificationCompat.Builder(applicationContext, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_popup_reminder)
                        .setContentTitle("MOMENTO")
                        .setContentText("Новый момент в секретной комнате")
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setAutoCancel(true)
                        .build())
                }
            }
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: MomentoApiException) {
            return if (failure.statusCode in 400..499 && failure.statusCode !in setOf(408, 429)) Result.success() else Result.retry()
        } catch (_: IOException) {
            return Result.retry()
        }
    }

    companion object {
        private const val ACCOUNT = "account_id"
        private const val CHANNEL = "momento_secret_rooms"

        private fun settings(context: Context, account: String) =
            context.getSharedPreferences("secret_alerts_${LocalMomentDatabase.databaseNameForAccount(account)}", Context.MODE_PRIVATE)

        fun enabled(context: Context, account: String, room: String) = settings(context, account).contains(room)

        fun configure(context: Context, account: String, room: String, enabled: Boolean, latestId: String? = null) {
            val store = settings(context, account)
            if (enabled) store.edit().putString(room, latestId.orEmpty()).apply()
            else {
                store.edit().remove(room).apply()
                context.getSystemService(NotificationManager::class.java).cancel(room.hashCode())
            }
            val work = WorkManager.getInstance(context)
            val name = "momento_secret_notifications_$account"
            if (store.all.isEmpty()) work.cancelUniqueWork(name)
            else work.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SecretRoomNotifications>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf(ACCOUNT to account))
                    .build())
        }
    }
}
