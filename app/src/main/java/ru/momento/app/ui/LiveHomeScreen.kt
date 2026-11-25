package ru.momento.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.momento.app.data.LocalSpace

/** Camera-first local home; never presents drafts as remotely published content. */
@Composable
fun LiveHomeScreen(
    rooms: List<LocalSpace>,
    activeRoomId: String?,
    onRecord: () -> Unit,
    onCreate: () -> Unit,
    onMine: () -> Unit,
    onOpen: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { MomentoScreenHeading("Комнаты", "Твои влоги и совместные истории") }
        item {
            val current = rooms.firstOrNull { it.id == activeRoomId }
            MomentoInfoCard(
                "Куда попадёт запись",
                current?.let { "Следующий момент будет сохранён в комнате «${it.name}»." }
                    ?: "Открой камеру, чтобы записать 2–5 секунд и сохранить момент.",
                "Открыть камеру", onRecord
            )
        }
        item {
            MomentoRoomTile("Создать комнату", "Объедини моменты одной темы в отдельную историю.",
                "", onClick = onCreate)
        }
        item { MomentoSectionTitle("Мои комнаты", "Открой историю, чтобы пересмотреть записанные моменты.") }
        if (rooms.isEmpty()) item {
            MomentoStatePanel("Комнат пока нет", "Создай комнату для дня, поездки или проекта.")
        }
        items(rooms.take(5), key = { it.id }) { room ->
            MomentoRoomTile(room.name, room.description,
                if (room.visibility == "PRIVATE") "Личная история · на устройстве" else "Комната · на устройстве",
                onClick = { onOpen(room.id) })
        }
        if (rooms.isNotEmpty()) item {
            OutlinedButton(onClick = onMine, modifier = Modifier.fillMaxWidth()) { Text("Все мои комнаты") }
        }
    }
}

@Composable
fun SubscriptionsPlaceholderScreen() {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Подписки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Здесь появятся продолжения влогов после подключения сервера и авторизации подписок.")
        Text("Пока публичные комнаты не загружены. Личные комнаты других пользователей недоступны.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
