package ru.momento.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MomentTest {
    @Test
    fun acceptsFiveSecondMoment() {
        val moment = Moment(
            id = "moment-1",
            ownerId = "user-1",
            localUri = "file:///private/moments/moment.mp4",
            capturedAtEpochMillis = 1_800_000_000_000,
            durationMillis = Moment.MAX_MOMENT_DURATION_MS
        )

        assertEquals(5_000L, moment.durationMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMomentLongerThanFiveSeconds() {
        Moment(
            id = "moment-too-long",
            ownerId = "user-1",
            localUri = "file:///private/moments/too-long.mp4",
            capturedAtEpochMillis = 1_800_000_000_000,
            durationMillis = Moment.MAX_MOMENT_DURATION_MS + 1
        )
    }
}
