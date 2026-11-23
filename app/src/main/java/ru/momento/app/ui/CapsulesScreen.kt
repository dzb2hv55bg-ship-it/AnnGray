package ru.momento.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.momento.app.network.MomentoApiClient
import ru.momento.app.network.RemoteCapsule

/** Read-only until video upload produces server-side moment IDs. */
@Composable
fun CapsulesScreen(accessToken: String, client: MomentoApiClient) {
    val scope = rememberCoroutineScope()
    var capsules by remember(accessToken) { mutableStateOf<List<RemoteCapsule>>(emptyList()) }
    var busy by remember(accessToken) { mutableStateOf(true) }
    var error by remember(accessToken) { mutableStateOf<String?>(null) }
    var refresh by remember(accessToken) { mutableIntStateOf(0) }
    var pendingCancel by remember { mutableStateOf<RemoteCapsule?>(null) }

    LaunchedEffect(accessToken, refresh) {
        busy = true
        error = null
        runCatching { withContext(Dispatchers.IO) { client.listCapsules(accessToken) } }
            .onSuccess { capsules = it }
            .onFailure { error = "Не удалось загрузить капсулы. Проверь подключение к серверу." }
        busy = false
    }
    pendingCancel?.let { capsule ->
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            title = { Text("Отменить капсулу?") },
            text = { Text("Капсула будет отменена. Сам видеомомент останется в архиве.") },
            confirmButton = { TextButton(onClick = {
                pendingCancel = null
                scope.launch {
                    busy = true
                    runCatching { client.cancelCapsule(accessToken, capsule.id) }
                        .onSuccess { refresh++ }
                        .onFailure { error = "Не удалось отменить капсулу."; busy = false }
                }
            }) { Text("Отменить капсулу") } },
            dismissButton = { TextButton(onClick = { pendingCancel = null }) { Text("Назад") } }
        )
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Капсула времени", style = MaterialTheme.typography.headlineSmall)
        Text("Сохраняй короткие видеомоменты на будущее. Открытие — не раньше чем через год.")
        Text("Создание капсул появится после синхронизации видео с сервером.", style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = { refresh++ }, enabled = !busy) { Text("Обновить") }
        if (!busy && error == null && capsules.isEmpty()) Text("Пока нет капсул времени")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(capsules, key = { it.id }) { capsule ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (capsule.unlocked) "Капсула открыта" else "Капсула закрыта")
                        Text("Дата открытия: ${capsule.releaseAt.take(10)}")
                        if (!capsule.unlocked) {
                            TextButton(onClick = { pendingCancel = capsule }, enabled = !busy) {
                                Text("Отменить")
                            }
                        }
                    }
                }
            }
        }
    }
}
