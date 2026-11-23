package ru.momento.app.ui

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import ru.momento.app.data.LocalMoment
import ru.momento.app.data.LocalSpace
import ru.momento.app.data.RoomMomentLink

/** Offline-only room editor. Never publishes local videos without server consent. */
@Composable
fun RoomMomentsScreen(
    room: LocalSpace,
    moments: List<LocalMoment>,
    links: List<RoomMomentLink>,
    onRemove: (String) -> Unit,
    onSeries: (String, String) -> Unit,
    onPlay: () -> Unit,
    onRecord: () -> Unit,
    onPublish: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(room.name, style = MaterialTheme.typography.headlineSmall)
        Text("${room.topic} · ${links.size} моментов · ${if (room.status == "DRAFT") "Черновик" else room.status}")
        Text("Только живая запись камерой. Моменты идут в хронологическом порядке. Публикация выполняется только по явному подтверждению.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRecord) { Text("Записать момент") }
            OutlinedButton(onClick = onPlay, enabled = links.isNotEmpty()) { Text("Смотреть") }
        }
        if (room.visibility == "PUBLIC") {
            Button(onClick = onPublish, enabled = links.isNotEmpty() && !room.publicationRequested && room.status != "COMPLETED") {
                Text(when {
                    room.publicationRequested -> "Ожидает публикации"
                    room.status == "PUBLISHED" -> "Опубликовать новые моменты"
                    else -> "Опубликовать открытый влог"
                })
            }
        }
        Text("Напоминания настраиваются в профиле: пять случайных сигналов на аккаунт, а не на комнату.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (links.isEmpty()) Text("В комнате пока нет моментов")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(links, key = { it.momentId }) { link ->
                val moment = moments.firstOrNull { it.id == link.momentId }
                if (moment != null) Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Момент ${link.position + 1} · ${moment.durationMillis / 1000.0} сек")
                        Text(link.series, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { onSeries(link.momentId, nextSeries(link.series)) }) { Text("Серия +") }
                            TextButton(onClick = { onRemove(link.momentId) }, enabled = !room.publicationRequested) { Text("Убрать") }
                        }
                    }
                }
            }
        }
        TextButton(onClick = onBack) { Text("Назад к комнатам") }
    }
}

private fun nextSeries(series: String): String {
    val current = Regex("^День (\\d+)$").matchEntire(series)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return "День ${current + 1}"
}

/** One player per room; clips are chronological, never auto-played simultaneously. */
@Composable
fun RoomVlogPlayer(room: LocalSpace, moments: List<LocalMoment>, onBack: () -> Unit) {
    val context = LocalContext.current
    val ordered = remember(moments) { moments.sortedWith(compareBy<LocalMoment> { it.capturedAtMillis }.thenBy { it.id }) }
    var index by remember(room.id) { mutableIntStateOf(0) }
    var playing by remember(room.id) { mutableStateOf(true) }
    var muted by remember(room.id) { mutableStateOf(false) }
    var error by remember(room.id) { mutableStateOf<String?>(null) }
    val player = remember(context, room.id) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (index < ordered.lastIndex) index++ else playing = false
                }
            }
            override fun onPlayerError(exception: androidx.media3.common.PlaybackException) {
                error = "Не удалось воспроизвести момент"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(ordered, index, player) {
        val moment = ordered.getOrNull(index)
        error = null
        if (moment == null) player.clearMediaItems()
        else {
            val file = java.io.File(moment.localPath)
            if (!file.isFile) { error = "Видео отсутствует на устройстве"; player.clearMediaItems() }
            else {
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
                player.playWhenReady = playing
            }
        }
    }
    LaunchedEffect(playing, muted, player) {
        player.playWhenReady = playing
        player.volume = if (muted) 0f else 1f
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(room.name, style = MaterialTheme.typography.titleLarge)
        Text("Момент ${if (ordered.isEmpty()) 0 else index + 1} из ${ordered.size}")
        if (ordered.isEmpty()) Text("В комнате пока нет доступных видео") else {
            AndroidView(
                factory = { PlayerView(it).apply { useController = false; this.player = player } },
                update = { it.player = player },
                modifier = Modifier.fillMaxWidth().weight(1f).pointerInput(index, ordered.size) {
                    var drag = 0f
                    detectHorizontalDragGestures(onHorizontalDrag = { _, amount -> drag += amount }, onDragEnd = {
                        if (drag < -60 && index < ordered.lastIndex) index++
                        if (drag > 60 && index > 0) index--
                        drag = 0f
                    })
                }
            )
            LinearProgressIndicator(progress = { (index + 1).toFloat() / ordered.size }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (error != null) TextButton(onClick = { error = null; player.prepare(); player.playWhenReady = true; playing = true }) { Text("Повторить") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (index > 0) index-- }, enabled = index > 0) { Text("Назад") }
                Button(onClick = { playing = !playing }) { Text(if (playing) "Пауза" else "Играть") }
                OutlinedButton(onClick = { muted = !muted }) { Text(if (muted) "Звук вкл." else "Без звука") }
                OutlinedButton(onClick = { if (index < ordered.lastIndex) index++ }, enabled = index < ordered.lastIndex) { Text("Далее") }
            }
        }
        TextButton(onClick = onBack) { Text("Закрыть просмотр") }
    }
}
