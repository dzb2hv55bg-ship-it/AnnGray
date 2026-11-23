package ru.momento.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RoomReminderRulesTest {
    @Test fun fiveRandomSlotsRespectWindowAndGapForManyDays() {
        repeat(1000) { day ->
            val slots = RoomReminderRules.slots("room-1", LocalDate.of(2026, 1, 1).plusDays(day.toLong()))
            assertEquals(5, slots.size)
            assertTrue(RoomReminderRules.valid(slots))
        }
    }
    @Test fun sameRoomAndDateReproducesScheduleAfterRestart() {
        val day = LocalDate.of(2026, 11, 22)
        assertEquals(RoomReminderRules.slots("room", day), RoomReminderRules.slots("room", day))
    }
    @Test fun invalidSlotsAreRejected() {
        assertFalse(RoomReminderRules.valid(listOf(540, 550, 600, 700, 800)))
        assertFalse(RoomReminderRules.valid(listOf(540, 600, 700, 800)))
        assertFalse(RoomReminderRules.valid(listOf(540, 600, 700, 800, 1260)))
    }
}
