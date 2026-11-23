package ru.momento.app.data

import java.time.LocalDate
import kotlin.random.Random

/** Five stable daily slots, 09:00 inclusive to 21:00 exclusive, separated by >=45 minutes. */
object RoomReminderRules {
    const val COUNT = 5
    const val START_MINUTE = 9 * 60
    const val END_MINUTE = 21 * 60
    const val MIN_GAP = 45

    fun slots(accountId: String, date: LocalDate): List<Int> {
        require(accountId.isNotBlank())
        val rng = Random((accountId + date.toString()).hashCode())
        // Reserve the minimum spacing, then randomly distribute the remaining slack.
        val slack = END_MINUTE - START_MINUTE - 1 - (COUNT - 1) * MIN_GAP
        val cuts = List(COUNT) { rng.nextInt(slack + 1) }.sorted()
        return cuts.mapIndexed { index, cut -> START_MINUTE + index * MIN_GAP + cut }
    }

    /** Do not make up missed reminders when enabled during the day. */
    fun remaining(slots: List<Int>, nowMinute: Int): List<Int> = slots.filter { it > nowMinute }

    fun valid(slots: List<Int>): Boolean = slots.size == COUNT &&
        slots.distinct().size == COUNT && slots.all { it in START_MINUTE until END_MINUTE } &&
        slots.zipWithNext().all { (a, b) -> b - a >= MIN_GAP }
}
