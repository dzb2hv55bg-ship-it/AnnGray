package ru.momento.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import ru.momento.app.network.MomentoApiException
import java.io.File
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.momento.app.network.MomentoApiClient
import ru.momento.app.network.RemoteSpace
import ru.momento.app.network.RemoteMoment

private data class FollowedRoom(val id: String, val name: String, val author: String)
private data class FollowedUpdate(val spaceId: String, val roomName: String, val momentId: String)

@Composable
fun SubscriptionsScreen(accessToken: String, api: MomentoApiClient, onOpenRoom: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var followed by remember { mutableStateOf<List<FollowedRoom>>(emptyList()) }
    var updates by remember { mutableStateOf<List<FollowedUpdate>>(emptyList()) }
    var discovered by remember { mutableStateOf<List<RemoteSpace>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var pendingRoom by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(accessToken, refreshKey) {
        loading = true
        error = null
        try {
            val subscriptions = api.getSubscriptions(accessToken)
            val roomArray = subscriptions.optJSONArray("rooms")
            followed = (0 until (roomArray?.length() ?: 0)).map { i ->
                val item = roomArray!!.getJSONObject(i)
                FollowedRoom(item.getString("id"), item.getString("name"), item.optString("author_name"))
            }
            val news = api.getSubscriptionUpdates(accessToken).optJSONArray("items")
            updates = (0 until (news?.length() ?: 0)).map { i ->
                val item = news!!.getJSONObject(i)
                FollowedUpdate(item.getString("space_id"), item.getString("room_name"), item.getString("moment_id"))
            }
            discovered = api.discoverSpaces(accessToken, search)
        } catch (exception: Exception) {
            error = exception.message ?: "Не удалось подключиться к серверу"
        } finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = MomentoTokens.screenPadding)) {
        MomentoScreenHeading("Подписки", "Следи за новыми видео авторов и комнат, на которые подписан.")
        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Найти открытые комнаты") }, singleLine = true)
        TextButton(onClick = { refreshKey++ }) { Text("Найти / обновить") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message ->
            MomentoStatePanel("Не удалось загрузить подписки", message, "Повторить", { refreshKey++ }, isError = true)
        }
        if (!loading && error == null) LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Мои комнаты в подписках", style = MaterialTheme.typography.titleMedium) }
            if (followed.isEmpty()) item { MomentoStatePanel("Пока нет подписок", "Подпишись на интересные комнаты, чтобы видеть новые моменты здесь.") }
            items(followed, key = { "follow_${it.id}" }) { room ->
                OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(room.name, style = MaterialTheme.typography.titleMedium)
                        Text(room.author)
                        Row {
                            TextButton(onClick = { onOpenRoom(room.id) }) { Text("Смотреть") }
                            TextButton(enabled = pendingRoom != room.id, onClick = {
                                pendingRoom = room.id
                                scope.launch {
                                    try { api.setRoomFollow(accessToken, room.id, false); refreshKey++ }
                                    catch (e: Exception) { error = e.message ?: "Ошибка отписки" }
                                    finally { pendingRoom = null }
                                }
                            }) { Text("Отписаться") }
                        }
                    }
                }
            }
            item { Text("Новые моменты", style = MaterialTheme.typography.titleMedium) }
            if (updates.isEmpty()) item { MomentoStatePanel("Нет новых моментов", "Когда авторы опубликуют видео, они появятся здесь.") }
            items(updates, key = { "update_${it.momentId}" }) { update ->
                MomentoRoomTile(update.roomName, "Открой комнату, чтобы посмотреть обновление.",
                    "Новый момент", onClick = { onOpenRoom(update.spaceId) })
            }
            item { Text("Открытые комнаты", style = MaterialTheme.typography.titleMedium) }
            items(discovered, key = { "discovery_${it.id}" }) { room ->
                OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(room.name, style = MaterialTheme.typography.titleMedium)
                        Text(room.description)
                        Row {
                            TextButton(onClick = { onOpenRoom(room.id) }) { Text("Открыть") }
                            if (followed.none { it.id == room.id }) {
                                Button(enabled = pendingRoom != room.id, onClick = {
                                    pendingRoom = room.id
                                    scope.launch {
                                        try { api.setRoomFollow(accessToken, room.id, true); refreshKey++ }
                                        catch (e: Exception) { error = e.message ?: "Ошибка подписки" }
                                        finally { pendingRoom = null }
                                    }
                                }) { Text("Подписаться") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PublicRoomScreen(
    accessToken: String, roomId: String, api: MomentoApiClient,
    sharedRoom: Boolean = false, onBack: () -> Unit
) {
    var moments by remember(roomId) { mutableStateOf<List<RemoteMoment>>(emptyList()) }
    var error by remember(roomId) { mutableStateOf<String?>(null) }
    var loading by remember(roomId) { mutableStateOf(true) }
    var retry by remember(roomId) { mutableIntStateOf(0) }
    var currentIndex by remember(roomId) { mutableIntStateOf(0) }
    val context = LocalContext.current
    var cachedVideo by remember(roomId, accessToken) { mutableStateOf<File?>(null) }
    var paused by remember(roomId) { mutableStateOf(false) }
    var muted by remember(roomId) { mutableStateOf(false) }
    var videoRetry by remember(roomId) { mutableIntStateOf(0) }
    var videoError by remember(roomId) { mutableStateOf<String?>(null) }
    val playbackFolder = remember(context, roomId) {
        File(context.cacheDir, "room-playback-${java.util.UUID.randomUUID()}").apply { mkdir() }
    }
    val player = remember(context, roomId) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && currentIndex < moments.lastIndex) currentIndex++
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                videoError = "Ошибка воспроизведения"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            // Server video cache is temporary; never leave private clips on disk after viewing.
            playbackFolder.deleteRecursively()
        }
    }
    LaunchedEffect(paused, muted, player) {
        player.playWhenReady = !paused
        player.volume = if (muted) 0f else 1f
    }

    val selected = moments.getOrNull(currentIndex)
    LaunchedEffect(roomId, selected?.id, accessToken, videoRetry) {
        player.stop(); player.clearMediaItems()
        cachedVideo?.delete()
        cachedVideo = null
        videoError = null
        if (selected == null) return@LaunchedEffect
        val file = File(playbackFolder, "${selected.id}.mp4")
        try {
            // Always reauthorize server videos, including previously cached private videos.
            // Offline playback of private server content is deliberately disabled.
            file.delete()
            cachedVideo = api.downloadMomentVideo(accessToken, selected.id, file)
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.playWhenReady = !paused
        }
        catch (e: CancellationException) {
            file.delete()
            throw e
        } catch (e: Exception) {
            videoError = if (e is MomentoApiException && e.statusCode == 401) "Сессия истекла. Войди снова"
                else e.message ?: "Не удалось получить видео"
        }
    }
    LaunchedEffect(roomId, accessToken, retry) {
        loading = true
        do {
            try {
                val page = api.getSpaceTimeline(accessToken, roomId, limit = if (sharedRoom) 50 else 20)
                val updated = page.items.asReversed()
                val currentId = moments.getOrNull(currentIndex)?.id
                val index = updated.indexOfFirst { it.id == currentId }
                currentIndex = index.coerceAtLeast(0)
                moments = updated
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                moments = emptyList()
                currentIndex = 0
                error = failure.message ?: "Ошибка загрузки комнаты"
            } finally {
                loading = false
            }
            if (sharedRoom) delay(15_000)
        } while (sharedRoom)
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text(if (sharedRoom || accessToken.isBlank()) "Назад к комнатам" else "Назад к подпискам") }
        MomentoScreenHeading("Моменты комнаты")
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { MomentoStatePanel("Не удалось получить влог", it, "Повторить", { retry++ }, isError = true) }
        if (!loading && error == null) {
            if (moments.isEmpty()) MomentoStatePanel("Пока нет моментов", "В этой комнате ещё нет доступных записей.")
            else {
                val activeMoment = moments[currentIndex.coerceIn(moments.indices)]
                Text("Момент ${currentIndex + 1} из ${moments.size}")
                Text("${activeMoment.capturedAt} · ${activeMoment.authorDisplayName}")
                videoError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val file = cachedVideo
                if (file != null) {
                    AndroidView(factory = { ctx -> PlayerView(ctx).apply {
                        useController = false
                        this.player = player
                    } }, update = { it.player = player },
                        modifier = Modifier.fillMaxWidth().weight(1f).pointerInput(currentIndex) {
                            var drag = 0f
                            detectHorizontalDragGestures(onHorizontalDrag = { _, dx -> drag += dx }, onDragEnd = {
                                if (drag < -60f && currentIndex < moments.lastIndex) currentIndex++
                                if (drag > 60f && currentIndex > 0) currentIndex--
                                drag = 0f
                            })
                        })
                } else if (videoError == null) CircularProgressIndicator()
                if (videoError != null) TextButton(onClick = { videoRetry++ }) { Text("Повторить загрузку") }
                Row {
                    TextButton(onClick = { paused = !paused }) { Text(if (paused) "Играть" else "Пауза") }
                    TextButton(onClick = { muted = !muted }) { Text(if (muted) "Включить звук" else "Без звука") }
                    TextButton(enabled = currentIndex > 0, onClick = { currentIndex-- }) { Text("Назад") }
                    TextButton(enabled = currentIndex < moments.lastIndex, onClick = { currentIndex++ }) { Text("Следующий") }
                }
            }
        }
    }
}
