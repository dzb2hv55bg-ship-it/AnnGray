import ru.momento.app.data.RoomReminderRules
import java.time.LocalDate

fun main() {
    val start = LocalDate.of(2026, 1, 1)
    var verified = 0
    for (account in 0 until 100) for (day in 0 until 40) {
        val id = "account-$account"
        val date = start.plusDays(day.toLong())
        val times = RoomReminderRules.slots(id, date)
        check(RoomReminderRules.valid(times)) { "Invalid slots: $id $date $times" }
        check(times == RoomReminderRules.slots(id, date)) { "Unstable slots: $id $date" }
        check(RoomReminderRules.remaining(times, 21 * 60).isEmpty())
        verified++
    }
    check(runCatching { RoomReminderRules.slots("", start) }.isFailure)
    println("PASS: $verified account-day reminder schedules; valid spacing, deterministic, no late replay, anonymous rejected")
}
