package ru.momento.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.momento.app.BuildConfig
import ru.momento.app.network.MomentoApiClient
import ru.momento.app.network.RemoteSpace

/** Guest is a UI state, never an implicit account or a substitute for an access token. */
@Composable
fun GuestHomeScreen(onPublish: () -> Unit, onSignIn: () -> Unit, onLeave: () -> Unit) {
    var rooms by remember { mutableStateOf<List<RemoteSpace>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(!BuildConfig.MOMENTO_API_CONFIGURED) }
    var openedRoom by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    val api = remember { MomentoApiClient() }
    LaunchedEffect(retry, searchQuery) {
        if (!BuildConfig.MOMENTO_API_CONFIGURED) {
            unavailable = true
            rooms = emptyList()
            return@LaunchedEffect
        }
        loading = true
        unavailable = false
        try {
            // Debounce guest catalog searches so every keystroke does not hit the API.
            if (searchQuery.isNotBlank()) delay(350)
            rooms = withContext(Dispatchers.IO) { api.discoverSpaces("", searchQuery.trim()) }
            unavailable = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unavailable = true
            rooms = emptyList()
        } finally { loading = false }
    }
    if (openedRoom != null) {
        PublicRoomScreen("", openedRoom!!, api, onBack = { openedRoom = null })
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { MomentoNavigationBar(selectedTab) { selectedTab = it } }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("MOMENTO", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            when (selectedTab) {
                0 -> {
                    MomentoSectionTitle("Жизнь в моментах", "Твои настоящие 2–5 секунд")
                    Text("Снимай короткие видео здесь и сейчас — 2–5 секунд.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MomentoPrimaryAction("Войти, чтобы записать момент", onPublish)
                    Text("Публичные влоги", style = MaterialTheme.typography.titleLarge)
                    PublicRooms(rooms, loading, unavailable, { retry++ }, { openedRoom = it }, searchQuery.isNotBlank())
                }
                1 -> {
                    MomentoSectionTitle("Открытые комнаты", "Находи людей и истории по интересам")
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { if (it.length <= 120) searchQuery = it },
                        label = { Text("Поиск открытых комнат") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    PublicRooms(rooms, loading, unavailable, { retry++ }, { openedRoom = it }, searchQuery.isNotBlank())
                }
                2 -> {
                    MomentoSectionTitle("Подписки")
                    Text("Чтобы подписываться на авторов и комнаты, войди в аккаунт.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Войти в аккаунт") }
                }
                else -> {
                    MomentoSectionTitle("Гостевой профиль")
                    Text("Ты смотришь MOMENTO без регистрации. Аккаунт понадобится для публикации и подписок.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Войти в аккаунт") }
                    OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("Вернуться ко входу") }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PublicRooms(
    rooms: List<RemoteSpace>, loading: Boolean, unavailable: Boolean,
    onRetry: () -> Unit, onOpen: (String) -> Unit, isSearching: Boolean
) {
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (unavailable) {
        MomentoStatePanel(
            "Публичные влоги пока недоступны",
            if (BuildConfig.MOMENTO_API_CONFIGURED)
                "Не удалось связаться с сервером. Проверь интернет и попробуй ещё раз."
            else "Сервер MOMENTO пока не подключён к этой сборке. Гостевой интерфейс доступен офлайн.",
            "Повторить", onRetry, isError = BuildConfig.MOMENTO_API_CONFIGURED
        )
    } else if (!loading && rooms.isEmpty()) {
        MomentoStatePanel(
            if (isSearching) "Ничего не найдено" else "Пока нет открытых влогов",
            if (isSearching) "Попробуй другое название или тему." else "Когда появятся опубликованные комнаты, они будут здесь."
        )
    }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(rooms, key = { it.id }) { room ->
            MomentoRoomTile(
                title = room.name, description = room.description,
                metadata = "Открытая комната · Смотреть влог", onClick = { onOpen(room.id) }
            )
        }
    }
}
