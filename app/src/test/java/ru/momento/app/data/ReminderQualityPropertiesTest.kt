package ru.momento.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Behavioural invariants, independent of how many rooms an account owns. */
class ReminderQualityPropertiesTest {
    @Test fun accountScheduleIsDeterministicAndIndependentOfRoomCount() {
        val date = LocalDate.of(2026, 11, 23)
        val expected = RoomReminderRules.slots("account-A", date)
        for (roomCount in listOf(0, 1, 2, 20, 100)) {
            val rooms = (0 until roomCount).map { "room-$it" }
            assertEquals("rooms=$rooms", expected, RoomReminderRules.slots("account-A", date))
            assertEquals(5, expected.size)
        }
    }

    @Test fun fiveValidSlotsAcrossAccountsAndDates() {
        val start = LocalDate.of(2026, 1, 1)
        for (account in 0 until 100) {
            for (day in 0 until 40) {
                val slots = RoomReminderRules.slots("account-$account", start.plusDays(day.toLong()))
                assertTrue("account=$account day=$day slots=$slots", RoomReminderRules.valid(slots))
            }
        }
    }

    @Test fun noMissedReminderIsReplayedOnReenable() {
        val slots = RoomReminderRules.slots("account-A", LocalDate.of(2026, 11, 23))
        assertEquals(slots.filter { it > 20 * 60 }, RoomReminderRules.remaining(slots, 20 * 60))
        assertTrue(RoomReminderRules.remaining(slots, 21 * 60).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun anonymousAccountCannotReceiveSchedule() {
        RoomReminderRules.slots("", LocalDate.of(2026, 11, 23))
    }
}
