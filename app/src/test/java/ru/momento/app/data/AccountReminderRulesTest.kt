package ru.momento.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AccountReminderRulesTest {
    @Test fun fivePerAccountNotPerRoom() {
        val date = LocalDate.of(2026, 11, 22)
        val times = RoomReminderRules.slots("account-1", date)
        assertEquals(5, times.size)
        assertTrue(RoomReminderRules.valid(times))
        assertEquals(times, RoomReminderRules.slots("account-1", date))
    }
    @Test fun allSlotsInDayWithGap() {
        repeat(1000) { day ->
            val slots = RoomReminderRules.slots("account", LocalDate.of(2026, 1, 1).plusDays(day.toLong()))
            assertTrue(RoomReminderRules.valid(slots))
            assertTrue(slots.all { it >= 540 && it < 1260 })
            assertTrue(slots.zipWithNext().all { (a, b) -> b - a >= 45 })
        }
    }
    @Test fun noCatchUpOnFirstPartialDay() {
        val slots = RoomReminderRules.slots("account", LocalDate.of(2026, 1, 1))
        assertTrue(RoomReminderRules.remaining(slots, 1190).all { it > 1190 })
        assertTrue(RoomReminderRules.remaining(slots, 1260).isEmpty())
    }
}
