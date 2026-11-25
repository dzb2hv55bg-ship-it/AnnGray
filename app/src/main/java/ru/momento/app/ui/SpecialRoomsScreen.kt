package ru.momento.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import ru.momento.app.data.SecretRoomNotifications
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.momento.app.network.CreatedInvite
import ru.momento.app.network.MomentoApiClient
import ru.momento.app.network.RemoteSpace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private fun eventDate(time: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mmXXX", Locale.ROOT).format(Date(time))

@Composable
internal fun SpecialRoomsScreen(
    token: String, accountId: String, api: MomentoApiClient,
    onRecord: (String, String) -> Unit, onBack: () -> Unit,
    previewOnly: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var roomId by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var film by rememberSaveable { mutableStateOf(false) }
    var playing by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var details by remember(roomId) { mutableStateOf<org.json.JSONObject?>(null) }
    var rooms by remember { mutableStateOf<List<RemoteSpace>>(emptyList()) }
    var members by remember(roomId) { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var invite by remember(roomId) { mutableStateOf<CreatedInvite?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notifications by remember(roomId) {
        mutableStateOf(roomId?.let { SecretRoomNotifications.enabled(context, accountId, it) } == true)
    }
    var permissionRoom by remember { mutableStateOf<String?>(null) }
    var confirmation by remember(roomId) { mutableStateOf<Pair<String, String?>?>(null) }

    fun submit(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
                refresh++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Не удалось выполнить действие"
            } finally {
                busy = false
            }
        }
    }

    fun enableNotifications(id: String) {
        submit {
            val marker = api.getSpaceTimeline(token, id, limit = 1).items.firstOrNull()?.id
            SecretRoomNotifications.configure(context, accountId, id, true, marker)
            if (roomId == id) notifications = true
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = permissionRoom
        permissionRoom = null
        if (granted && id != null) enableNotifications(id)
        else if (!granted) error = "Системное разрешение на уведомления не предоставлено."
    }

    fun back() {
        if (busy) return
        error = null
        when {
            film -> film = false
            playing -> playing = false
            kind != null -> kind = null
            roomId != null -> roomId = null
            else -> onBack()
        }
    }

    LaunchedEffect(token, roomId, refresh, playing, film) {
        if (previewOnly || playing || film) return@LaunchedEffect
        do {
            try {
                val id = roomId
                if (id == null) {
                    rooms = api.listSpaces(token).filter { it.kind == "EVENT" || it.kind == "SECRET" }
                } else {
                    val loaded = api.specialRoomDetails(token, id)
                    val list = api.specialRoomMembers(token, id)
                    members = List(list.length()) { list.getJSONObject(it) }
                    details = loaded
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                details = null
                error = failure.message ?: "Не удалось загрузить комнаты"
            }
            delay(15_000)
        } while (roomId != null)
    }

    BackHandler { back() }
    if (film) {
        val id = roomId ?: return
        EventFilmScreen(token, id, api) { film = false }
        return
    }
    if (playing) {
        val id = roomId ?: return
        PublicRoomScreen(token, id, api, sharedRoom = true, onBack = { playing = false })
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MomentoTokens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { TextButton(onClick = { back() }, enabled = !busy) { Text("Назад") } }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

        val type = kind
        val id = roomId
        when {
            type != null -> item {
                var name by rememberSaveable { mutableStateOf("") }
                var description by rememberSaveable { mutableStateOf("") }
                var starts by rememberSaveable { mutableStateOf(eventDate(System.currentTimeMillis() - 60_000)) }
                var ends by rememberSaveable { mutableStateOf(eventDate(System.currentTimeMillis() + 86_400_000)) }
                var openEvent by rememberSaveable { mutableStateOf(false) }
                val requestId = rememberSaveable { UUID.randomUUID().toString() }
                val event = type == "EVENT"
                MomentoScreenHeading(if (event) "Комната события" else "Секретная комната")
                Text(if (event) "Приглашённые участники соберут свои моменты в общую хронологию."
                    else "Комната для двух участников. Чтобы добавить второго, отправь одноразовое приглашение.")
                if (previewOnly) Text("Можно заполнить форму. Для создания комнаты потребуется вход и подключение к серверу.")
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("Описание") }, modifier = Modifier.fillMaxWidth())
                if (event) {
                    OutlinedTextField(starts, { starts = it }, label = { Text("Начало с часовым поясом") }, singleLine = true)
                    OutlinedTextField(ends, { ends = it }, label = { Text("Завершение с часовым поясом") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = openEvent, onCheckedChange = { openEvent = it })
                        Column {
                            Text("Разрешить публичную публикацию")
                            Text("После публикации историю смогут посмотреть все.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                MomentoPrimaryAction("Создать", enabled = !previewOnly && !busy && name.trim().length in 2..60 && description.length <= 500, onClick = {
                    submit {
                        roomId = api.createSpecialRoom(token, requestId, type, name, description,
                            starts.takeIf { event }, ends.takeIf { event }, event && openEvent).id
                        kind = null
                    }
                })
            }
            id != null -> {
                val room = details
                if (room == null) {
                    item { TextButton(onClick = { refresh++ }) { Text("Обновить") } }
                } else {
                    val event = room.optString("kind") == "EVENT"
                    val owner = room.optString("owner_id") == accountId
                    val completed = !room.isNull("completed_at")
                    item {
                        MomentoScreenHeading(room.getString("name"), if (event) "Комната события" else "Секретная комната")
                        if (room.optString("description").isNotBlank()) Text(room.optString("description"))
                        if (event) {
                            Text("Начало · ${room.optString("event_starts_at")}")
                            Text("Завершение · ${room.optString("event_ends_at")}")
                        }
                        if (completed) Text("Событие завершено")
                        if (!completed) MomentoPrimaryAction("Записать момент", onClick = { onRecord(id, room.getString("name")) })
                        TextButton(onClick = { playing = true }) { Text("Смотреть хронологию") }
                        if (event) TextButton(onClick = { film = true }) { Text("Создать фильм события") }
                    }
                    if (!event) item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = notifications, enabled = !busy, onCheckedChange = { enabled ->
                                if (!enabled) {
                                    SecretRoomNotifications.configure(context, accountId, id, false)
                                    notifications = false
                                } else if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    permissionRoom = id
                                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else enableNotifications(id)
                            })
                            Text("Уведомлять о новых моментах")
                        }
                    }
                    item { MomentoSectionTitle("Участники") }
                    items(members, key = { it.getString("user_id") }) { member ->
                        val memberId = member.getString("user_id")
                        Text(member.optString("display_name").ifBlank { "Участник" } +
                            if (member.optString("role") == "OWNER") " · владелец" else "")
                        if (owner && memberId != accountId) TextButton(enabled = !busy, onClick = {
                            confirmation = "remove" to memberId
                        }) { Text("Отозвать доступ") }
                    }
                    if (owner) item {
                        if (!completed && (event || members.size < 2)) {
                            Button(enabled = !busy, onClick = { submit { invite = api.createInvite(token, id, maxUses = 1) } }) {
                                Text("Создать приглашение")
                            }
                        }
                        invite?.let { link ->
                            TextButton(onClick = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    this.type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, link.inviteUrl)
                                }
                                context.startActivity(Intent.createChooser(share, "Пригласить участника"))
                            }) { Text("Отправить приглашение") }
                            TextButton(enabled = !busy, onClick = {
                                submit { api.revokeInvite(token, id, link.inviteId); invite = null }
                            }) { Text("Отозвать приглашение") }
                        }
                        if (event && !completed && room.optString("visibility") == "PUBLIC" && room.isNull("published_at")) {
                            TextButton(onClick = { confirmation = "publish" to null }) { Text("Опубликовать событие") }
                        }
                        if (event && !completed) TextButton(onClick = { confirmation = "complete" to null }) {
                            Text("Завершить событие")
                        }
                        TextButton(onClick = { confirmation = "archive" to null }) { Text("Удалить комнату") }
                    } else item {
                        TextButton(onClick = { confirmation = "leave" to null }) { Text("Покинуть комнату") }
                    }
                }
            }
            else -> {
                item {
                    var code by rememberSaveable { mutableStateOf("") }
                    MomentoScreenHeading("События и секретные комнаты", "Совместная история из коротких видео")
                    if (previewOnly) Text("Здесь можно осмотреть формы. Совместная история и приглашения доступны после входа.")
                    MomentoPrimaryAction(if (previewOnly) "Посмотреть комнату события" else "Создать комнату события",
                        enabled = !busy, onClick = { kind = "EVENT" })
                    TextButton(onClick = { kind = "SECRET" }, enabled = !busy) {
                        Text(if (previewOnly) "Посмотреть секретную комнату" else "Создать секретную комнату")
                    }
                    if (!previewOnly) {
                        OutlinedTextField(code, { code = it }, label = { Text("Ссылка или код приглашения") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = code.isNotBlank() && !busy, onClick = {
                            submit { roomId = api.acceptInvite(token, code.trim().trimEnd('/').substringAfterLast('/')) }
                        }) { Text("Присоединиться") }
                        TextButton(onClick = { refresh++ }) { Text("Обновить список") }
                    }
                }
                items(rooms, key = { it.id }) { room ->
                    OutlinedButton(onClick = { roomId = room.id; error = null }, modifier = Modifier.fillMaxWidth()) {
                        Text(room.name)
                    }
                }
            }
        }
    }

    confirmation?.let { (action, target) ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("Подтвердить действие") },
            text = { Text(when (action) {
                "archive" -> "Комната исчезнет у участников. Личные записи останутся у авторов."
                "leave" -> "Ты потеряешь доступ к общей истории."
                "publish" -> "Историю смогут смотреть все. Для публикации в событии должен быть хотя бы один момент."
                "complete" -> "Новые записи будут закрыты. История останется доступной участникам."
                else -> "Участник потеряет доступ к общей истории."
            }) },
            confirmButton = {
                TextButton(onClick = {
                    val id = roomId ?: return@TextButton
                    confirmation = null
                    submit {
                        if (action == "publish") api.publishRoom(token, id)
                        else api.changeSpecialRoom(token, id, action, target)
                        if (action == "archive" || action == "leave") {
                            SecretRoomNotifications.configure(context, accountId, id, false)
                            roomId = null
                        }
                        if (action == "remove") invite = null
                    }
                }) { Text("Подтвердить") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Отмена") } }
        )
    }
}
