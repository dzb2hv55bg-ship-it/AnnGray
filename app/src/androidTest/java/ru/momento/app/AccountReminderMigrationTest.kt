package ru.momento.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import ru.momento.app.data.RoomAlarmScheduler

/** Exercises actual SharedPreferences migration, not text matching. */
@RunWith(AndroidJUnit4::class)
class AccountReminderMigrationTest {
    @Test fun oldRoomPreferencesBecomeOneAccountSchedule() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("momento_account_alarms", 0)
        try {
            val old = JSONObject().put("rooms", JSONObject().put("room-a", "A").put("room-b", "B"))
                .put("selected", "room-b").put("pending", 123456L)
            assertTrue(prefs.edit().clear().putString("account", "test-owner")
                .putString("state", old.toString()).commit())
            RoomAlarmScheduler.migrateLegacy(context)
            assertTrue(RoomAlarmScheduler.isAccountEnabled(context))
            assertEquals("room-b", RoomAlarmScheduler.recordingTarget(context)?.first)
            val migrated = JSONObject(prefs.getString("state", "{}")!!)
            assertFalse(migrated.has("rooms"))
            assertFalse(migrated.has("pending"))
            RoomAlarmScheduler.reconcile(context, setOf("room-a"))
            assertNull(RoomAlarmScheduler.recordingTarget(context))
            assertTrue(RoomAlarmScheduler.isAccountEnabled(context))
            RoomAlarmScheduler.setEnabled(context, false)
            assertFalse(RoomAlarmScheduler.isAccountEnabled(context))
        } finally {
            RoomAlarmScheduler.disableAll(context)
        }
    }

    @Test fun switchingOwnerClearsPreviousSchedule() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            RoomAlarmScheduler.setAccount(context, "owner-one")
            RoomAlarmScheduler.setEnabled(context, true)
            RoomAlarmScheduler.setAccount(context, "owner-two")
            assertFalse(RoomAlarmScheduler.isAccountEnabled(context))
            assertNull(RoomAlarmScheduler.recordingTarget(context))
        } finally { RoomAlarmScheduler.disableAll(context) }
    }
}
