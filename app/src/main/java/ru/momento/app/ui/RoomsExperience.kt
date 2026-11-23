package ru.momento.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.momento.app.data.LocalSpace

private val topics = listOf("День", "Путешествие", "Событие", "Семья", "Спорт", "Настроение", "Проект", "Другое")

/** Only locally owned rooms are shown. Never misrepresent them as public server listings. */
@Composable
fun RoomsDiscoveryScreen(rooms: List<LocalSpace>, onCreate: () -> Unit, onMine: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("Для вас") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Комнаты", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Живые мини-влоги, истории и события", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50)) {
                Text("＋  Создать комнату")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Для вас", "Новые", "Подписки").forEach { option ->
                    FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
                }
            }
        }
        item {
            Text("Открытые комнаты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Публичный каталог появится после подключения сервера. Мы не показываем чужие личные комнаты.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedButton(onClick = onMine, modifier = Modifier.fillMaxWidth()) { Text("Открыть мои комнаты (${rooms.size})") }
        }
    }
}

@Composable
fun MyRoomsScreen(rooms: List<LocalSpace>, onCreate: () -> Unit, onOpen: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("Все") }
    val visible = rooms.filter { filter == "Все" || when (filter) {
        "Черновики" -> it.status == "DRAFT"
        "Личные" -> it.visibility != "PUBLIC"
        else -> it.status == "PUBLISHED"
    } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Мои комнаты", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Все", "Черновики", "Личные").forEach { option ->
                    FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
                }
            }
        }
        if (visible.isEmpty()) item {
            MomentoStatePanel("Комнат пока нет", "Создай комнату, чтобы сохранять живые моменты.")
        }
        items(visible, key = { it.id }) { room -> RoomDraftCard(room, onOpen) }
        item { MomentoPrimaryAction("Создать комнату", onCreate) }
    }
}

@Composable
private fun RoomDraftCard(room: LocalSpace, onOpen: (String) -> Unit) {
    MomentoRoomTile(
        title = room.name,
        description = room.description,
        metadata = "${room.topic} · ${if (room.visibility == "PUBLIC") "Открытая" else if (room.visibility == "LINK") "По ссылке" else "Личная"} · ${if (room.status == "DRAFT") "Черновик" else "Сохранена локально"}",
        onClick = { onOpen(room.id) },
        footer = "Хранится на устройстве. Публикация требует подтверждения сервера."
    )
}

@Composable
fun RoomSearchScreen(rooms: List<LocalSpace>, onOpen: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(query, rooms) {
        rooms.filter { room -> room.name.contains(query, true) || room.description.contains(query, true) || room.topic.contains(query, true) }
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Поиск комнат", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(value = query, onValueChange = { query = it.take(100) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Название, тема или описание") }, shape = RoundedCornerShape(20.dp)) }
        item { Text("Локальный поиск по моим комнатам", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (matches.isEmpty()) item { Text("Ничего не найдено") }
        items(matches, key = { it.id }) { RoomDraftCard(it, onOpen) }
        item { Text("Поиск публичных комнат и авторов требует подключения сервера.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun CreateRoomScreen(onSave: (String, String, String, String) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var visibility by rememberSaveable { mutableStateOf("PRIVATE") }
    var topic by rememberSaveable { mutableStateOf("День") }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { MomentoScreenHeading("Создать комнату", "Выбери, с кем будешь делиться живыми моментами") }
        item { MomentoSectionTitle("Видимость") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("PRIVATE" to "Личная", "PUBLIC" to "Открытая", "LINK" to "По ссылке").forEach { (value, title) ->
                    FilterChip(selected = visibility == value, onClick = { visibility = value }, label = { Text(title) })
                }
            }
        }
        item { MomentoSectionTitle("Тема комнаты") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                topics.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { value -> FilterChip(selected = topic == value, onClick = { topic = value }, label = { Text(value) }) }
                    }
                }
            }
        }
        item { OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("Название комнаты") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), singleLine = true) }
        item { OutlinedTextField(value = description, onValueChange = { description = it.take(500) }, label = { Text("Описание (необязательно)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), minLines = 3) }
        item { MomentoStatePanel("Сначала черновик", when (visibility) { "PRIVATE" -> "Личная комната видна только тебе. Публикация не выполняется автоматически."; "LINK" -> "Совместная комната требует приглашения и серверной проверки доступа."; else -> "Открытая комната станет доступна другим только после отдельной публикации на сервере." }) }
        item { MomentoPrimaryAction("Сохранить черновик", onClick = { onSave(name.trim(), description.trim(), visibility, topic) }, enabled = name.trim().isNotEmpty()) }
        item { OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Отмена") } }
    }
}
