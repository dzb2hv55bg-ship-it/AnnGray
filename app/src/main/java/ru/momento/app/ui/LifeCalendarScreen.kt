package ru.momento.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.momento.app.data.LocalMoment
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Local-only calendar. Server-side history is not silently mixed into this view. */
private enum class LifePeriod(val title: String) { WEEK("Неделя"), MONTH("Месяц"), ALL("Всё время") }

@Composable
fun LifeCalendarScreen(moments: List<LocalMoment>) {
    var period by remember { mutableStateOf(LifePeriod.MONTH) }
    val today = remember { Calendar.getInstance() }
    val start = remember(period) {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            when (period) {
                LifePeriod.WEEK -> add(Calendar.DAY_OF_YEAR, -6)
                LifePeriod.MONTH -> set(Calendar.DAY_OF_MONTH, 1)
                LifePeriod.ALL -> setTimeInMillis(0L)
            }
        }.timeInMillis
    }
    val dayFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) }
    val displayFormatter = remember { SimpleDateFormat("dd MMMM yyyy", Locale.forLanguageTag("ru")) }
    val groups = remember(moments.toList(), period) {
        moments.filter { it.capturedAtMillis >= start && it.capturedAtMillis <= System.currentTimeMillis() }
            .groupBy { dayFormatter.format(Date(it.capturedAtMillis)) }
            .toSortedMap(reverseOrder())
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Календарь жизни", style = MaterialTheme.typography.headlineSmall)
        Text("Только видео, сохранённые на этом устройстве", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LifePeriod.entries.forEach { option ->
                FilterChip(selected = period == option, onClick = { period = option }, label = { Text(option.title) })
            }
        }
        Text("Моментов: ${groups.values.sumOf { it.size }}")
        if (groups.isEmpty()) {
            Text("В выбранном периоде пока нет моментов")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups.entries.toList(), key = { it.key }) { entry ->
                    val date = runCatching { dayFormatter.parse(entry.key) }.getOrNull()
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(date?.let { displayFormatter.format(it) } ?: entry.key, style = MaterialTheme.typography.titleMedium)
                            Text("Видео: ${entry.value.size}")
                        }
                    }
                }
            }
        }
    }
}
