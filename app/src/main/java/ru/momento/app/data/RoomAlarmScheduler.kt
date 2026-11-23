package ru.momento.app.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import ru.momento.app.MainActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Exactly one account-scoped pending alarm; the persisted cursor prevents duplicate delivery. */
class RoomAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getLongExtra("slot_epoch", -1L)
        if (!RoomAlarmScheduler.consume(context, slot)) {
            RoomAlarmScheduler.reschedule(context)
            return
        }
        val target = RoomAlarmScheduler.recordingTarget(context)
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("momento_record", "Напоминания о моментах", NotificationManager.IMPORTANCE_DEFAULT))
            val open = Intent(context, MainActivity::class.java).apply {
                target?.first?.let { putExtra("record_room_id", it) }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val click = PendingIntent.getActivity(context, 84132, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(84132, NotificationCompat.Builder(context, "momento_record")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Пора зафиксировать момент")
                .setContentText(target?.second?.let { "Запиши момент для комнаты «$it»" } ?: "Открой MOMENTO и запиши живой момент")
                .setContentIntent(click).setAutoCancel(true).build())
        }
        RoomAlarmScheduler.reschedule(context)
    }
}

class RoomBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            RoomAlarmScheduler.rescheduleAll(context)
        }
    }
}

/** One account-wide reminder schedule. Room preferences select the recording target,
 * never create additional alarms. Persisted cursor prevents duplicate deliveries. */
object RoomAlarmScheduler {
    private const val PREFS = "momento_account_alarms"
    private const val ALARM_ID = 84132
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun alarm(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private fun rooms(data: JSONObject) = data.optJSONObject("rooms") ?: JSONObject()

    @Synchronized fun setAccount(context: Context, accountId: String) {
        val p = prefs(context)
        val old = p.getString("account", null)
        if (old != accountId) {
            cancel(context)
            p.edit().clear().putString("account", accountId).commit()
        }
        migrateLegacy(context)
        reschedule(context)
    }
    private fun load(context: Context) = runCatching {
        JSONObject(prefs(context).getString("state", "{}") ?: "{}")
    }.getOrDefault(JSONObject())
    private fun save(context: Context, data: JSONObject) {
        check(prefs(context).edit().putString("state", data.toString()).commit())
    }
    /** Upgrade old per-room preferences to a single account-wide opt-in. */
    @Synchronized fun migrateLegacy(context: Context) {
        val data = load(context)
        if (data.has("enabled")) return
        val legacy = rooms(data)
        data.put("enabled", legacy.length() > 0)
        // Preserve only the selected recording destination; never the old room alarm count.
        val selected = data.optString("selected")
        if (legacy.has(selected)) {
            data.put("targetRoom", selected).put("targetName", legacy.optString(selected))
        }
        data.remove("rooms")
        data.remove("selected")
        data.remove("pending")
        save(context, data)
        cancel(context)
    }
    fun isAccountEnabled(context: Context): Boolean = load(context).optBoolean("enabled", false)
    fun recordingTarget(context: Context): Pair<String, String>? {
        val data = load(context)
        val id = data.optString("targetRoom").takeIf { it.isNotBlank() } ?: return null
        return id to data.optString("targetName", "Комната")
    }
    @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
        migrateLegacy(context)
        val data = load(context)
        data.put("enabled", enabled).remove("pending")
        save(context, data)
        reschedule(context)
    }
    @Synchronized fun selectRecordingTarget(context: Context, roomId: String?, roomName: String? = null) {
        migrateLegacy(context)
        val data = load(context)
        if (roomId.isNullOrBlank()) {
            data.remove("targetRoom").remove("targetName")
        } else {
            data.put("targetRoom", roomId).put("targetName", roomName.orEmpty())
        }
        save(context, data)
        reschedule(context)
    }
    @Synchronized fun reconcile(context: Context, activeRoomIds: Set<String>) {
        migrateLegacy(context)
        val data = load(context)
        if (data.optString("targetRoom") !in activeRoomIds) {
            data.remove("targetRoom").remove("targetName")
            save(context, data)
        }
        reschedule(context)
    }
    @Synchronized fun disableAll(context: Context) {
        cancel(context)
        prefs(context).edit().clear().commit()
    }
    fun canUseExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || alarm(context).canScheduleExactAlarms()
    fun exactAlarmSettingsIntent(context: Context): Intent? = if (Build.VERSION.SDK_INT >= 31) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    } else null
    fun rescheduleAll(context: Context) = reschedule(context)
    @Synchronized fun consume(context: Context, epoch: Long): Boolean {
        val data = load(context)
        if (!data.optBoolean("enabled", false) || epoch <= 0 || epoch != data.optLong("pending", -1L)) return false
        val now = java.time.ZonedDateTime.now(ZoneId.systemDefault())
        val expected = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault())
        val day = now.toLocalDate().toString()
        if (now.hour !in 9..20 || expected.toLocalDate() != now.toLocalDate() || epoch > System.currentTimeMillis() + 60_000L) {
            data.remove("pending")
            save(context, data)
            return false
        }
        val count = if (data.optString("deliveredDay") == day) data.optInt("deliveredCount") else 0
        if (count >= RoomReminderRules.COUNT) return false
        data.put("deliveredDay", day).put("deliveredCount", count + 1)
        data.put("delivered", epoch).remove("pending")
        save(context, data)
        return true
    }
    @Synchronized fun reschedule(context: Context) {
        cancel(context)
        val account = prefs(context).getString("account", null) ?: return
        val data = load(context)
        if (!data.optBoolean("enabled", false)) { data.remove("pending"); save(context, data); return }
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val day = today.toString()
        val count = if (data.optString("deliveredDay") == day) data.optInt("deliveredCount") else 0
        val last = data.optLong("delivered", -1L)
        val next = (0L..1L).asSequence().flatMap { offset ->
            val date = today.plusDays(offset)
            val slots = if (offset == 0L && count >= RoomReminderRules.COUNT) emptyList()
                        else RoomReminderRules.slots(account, date)
            slots.asSequence().map { minute ->
                date.atStartOfDay().plusMinutes(minute.toLong()).atZone(zone).toInstant().toEpochMilli()
            }
        }.firstOrNull { it > now + 1_000L && it > last } ?: return
        data.put("pending", next)
        save(context, data)
        val intent = Intent(context, RoomAlarmReceiver::class.java)
            .putExtra("slot_epoch", next)
        val pending = PendingIntent.getBroadcast(context, ALARM_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (canUseExactAlarms(context)) alarm(context).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            else alarm(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        } catch (_: SecurityException) {
            alarm(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        }
    }
    private fun cancel(context: Context) {
        val pending = PendingIntent.getBroadcast(context, ALARM_ID,
            Intent(context, RoomAlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) { alarm(context).cancel(pending); pending.cancel() }
    }
}
