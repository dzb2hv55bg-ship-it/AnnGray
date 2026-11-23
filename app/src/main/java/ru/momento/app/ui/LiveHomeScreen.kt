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
        item {
            Text("Твой момент — сейчас", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Живые истории по 2–5 секунд", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Button(onClick = onRecord, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50.dp)) {
                Text("◉  Записать момент")
            }
        }
        item {
            val current = rooms.firstOrNull { it.id == activeRoomId }
            Text("Комната для записи: ${current?.name ?: "выбери в камере"}", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMine) { Text("Мои комнаты") }
                OutlinedButton(onClick = onCreate) { Text("+ Создать") }
            }
        }
        item { Text("Мои истории", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (rooms.isEmpty()) item {
            Text("Комнат пока нет. Создай первую и запиши живой момент.")
        }
        items(rooms.take(5), key = { it.id }) { room ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(room.id) }, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(room.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (room.visibility == "PRIVATE") "Личная история · на устройстве" else "Комната · на устройстве", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
