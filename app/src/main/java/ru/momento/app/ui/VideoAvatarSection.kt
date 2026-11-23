package ru.momento.app.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ru.momento.app.data.LocalMoment

/** Session-local video avatar preview. Never uploads or publishes the chosen clip. */
@Composable
fun VideoAvatarSection(moments: List<LocalMoment>) {
    var chosenId by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    val eligible = moments.filter { it.durationMillis in 2000L..5000L && it.file.isFile && it.file.length() > 0L }
    val chosen = eligible.firstOrNull { it.id == chosenId }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Живой видеоаватар", style = MaterialTheme.typography.titleLarge)
        Text("Выбери свой момент длительностью 2–5 секунд. Пока это только предпросмотр на этом устройстве.", style = MaterialTheme.typography.bodySmall)
        if (chosen != null) {
            key(chosen.id) {
                AndroidView(
                    factory = { context -> VideoView(context).apply {
                        setVideoURI(Uri.fromFile(chosen.file))
                        setOnPreparedListener { player -> player.isLooping = true; start() }
                    } },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    onRelease = { it.stopPlayback() }
                )
            }
            TextButton(onClick = { chosenId = null }) { Text("Убрать видеоаватар") }
        }
        Button(onClick = { showPicker = true }, enabled = eligible.isNotEmpty()) {
            Text(if (chosen == null) "Выбрать видеоаватар" else "Сменить видеоаватар")
        }
        if (eligible.isEmpty()) Text("Сначала запиши момент продолжительностью 2–5 секунд.")
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Выбери момент") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    eligible.take(12).forEachIndexed { index, moment ->
                        TextButton(onClick = { chosenId = moment.id; showPicker = false }) {
                            Text("Момент ${index + 1} · ${moment.durationMillis / 1000.0} с")
                        }
                    }
                    if (eligible.size > 12) Text("Показаны последние 12 подходящих видео")
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("Закрыть") } }
        )
    }
}
