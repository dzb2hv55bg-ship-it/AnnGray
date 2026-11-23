package ru.momento.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest
import com.vk.id.VKID
import com.vk.id.logout.VKIDLogoutCallback
import com.vk.id.logout.VKIDLogoutFail
import android.net.Uri
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.momento.app.data.LocalMoment
import ru.momento.app.data.LocalMomentDatabase
import ru.momento.app.data.LocalSpace
import ru.momento.app.data.LocalSpaceDatabase
import ru.momento.app.network.MomentoSessionVault
import ru.momento.app.network.MomentoApiClient
import ru.momento.app.network.MomentoAuthClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Tab(val label: String) {
    SPACES("Комнаты"), SUBSCRIPTIONS("Подписки"), CREATE("Создать"), MINE("Мои"), SEARCH("Поиск"), PROFILE("Профиль"), ARCHIVE("Архив"), CALENDAR("Календарь"), CAPSULES("Капсулы"), RECORD("Момент"), ROOM_DETAIL("Комната"), ROOM_PLAYER("Просмотр")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentoApp() {
    val launchActivity = LocalActivity.current
    val initialRoom = launchActivity?.intent?.getStringExtra("record_room_id")
    var selectedTab by rememberSaveable { mutableStateOf(if (initialRoom != null) Tab.RECORD else Tab.SPACES) }
    var archiveError by rememberSaveable { mutableStateOf<String?>(null) }
    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) archiveError = "Уведомления отключены. Разреши их в настройках Android."
    }
    val context = LocalContext.current
    val exactAlarmSettingsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        ru.momento.app.data.RoomAlarmScheduler.rescheduleAll(context)
        archiveError = if (ru.momento.app.data.RoomAlarmScheduler.canUseExactAlarms(context))
            "Точные напоминания разрешены" else "Уведомления могут приходить с задержкой"
    }
    val sessionVault = remember(context) { MomentoSessionVault(context) }
    var authSession by remember { mutableStateOf(sessionVault.read()) }
    var logoutBusy by remember { mutableStateOf(false) }
    val authClient = remember { MomentoAuthClient(ru.momento.app.BuildConfig.MOMENTO_API_BASE_URL) }
    LaunchedEffect(authSession?.refreshToken) {
        val initial = authSession ?: return@LaunchedEffect
        val expectedToken = initial.refreshToken
        while (authSession?.refreshToken == expectedToken) {
            val waitMillis = initial.expiresAtMillis - System.currentTimeMillis() - 30_000L
            if (waitMillis > 0L) delay(waitMillis)
            if (authSession?.refreshToken != expectedToken) break
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val owner = sessionVault.readVerifiedAccountId()
                    if (owner == null) null
                    else ru.momento.app.network.MomentoSessionRefresh.refreshForOwner(sessionVault, owner)
                }
            }
            val refreshed = result.getOrNull()
            if (refreshed != null) {
                // The worker may have refreshed this session first. Do not overwrite it.
                if (sessionVault.read()?.refreshToken == refreshed.refreshToken) authSession = refreshed
                break
            }
            val failure = result.exceptionOrNull()
            if (failure is ru.momento.app.network.MomentoApiException && failure.statusCode == 401) {
                // Never clear a newer session that replaced the rejected token.
                if (sessionVault.read()?.refreshToken == expectedToken) {
                    withContext(Dispatchers.IO) { sessionVault.clear() }
                    authSession = null
                }
                break
            }
            delay(15_000L)
        }
    }
    var guestMode by rememberSaveable { mutableStateOf(false) }
    var guestTab by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var guestPublishing by rememberSaveable { mutableStateOf(false) }
    var guestLoginOnly by rememberSaveable { mutableStateOf(false) }
    if (authSession == null && guestMode && !guestPublishing) {
        GuestHomeScreen(
            selectedTab = guestTab,
            onTabSelected = { guestTab = it },
            onPublish = { guestPublishing = true; guestLoginOnly = false },
            onSignIn = { guestPublishing = true; guestLoginOnly = true },
            onLeave = { guestMode = false; guestTab = 0 }
        )
        return
    }
    if (authSession == null) {
        if (guestPublishing) {
            androidx.compose.material3.TextButton(onClick = { guestPublishing = false }) {
                Text("Отмена — вернуться в гостевой режим")
            }
            if (!guestLoginOnly) Text("Чтобы записывать и публиковать моменты, сначала войди в аккаунт.", modifier = Modifier.padding(16.dp))
        }
        AuthScreen(vault = sessionVault,
            onAuthenticated = { authSession = it; if (guestPublishing && !guestLoginOnly) selectedTab = Tab.RECORD; guestPublishing = false; guestMode = false; guestTab = 0 },
            onGuest = { guestMode = true; guestPublishing = false })
        return
    }
    // Fail closed until /auth/me verifies which account owns the local archive.
    var verifiedAccountId by remember(authSession?.accessToken) { mutableStateOf(sessionVault.readVerifiedAccountId()) }
    LaunchedEffect(authSession?.accessToken) {
        val token = authSession?.accessToken ?: return@LaunchedEffect
        // A cached, server-verified identity permits offline use of this account's archive.
        // Never replace it with an unverified JWT claim or a different server identity.
        val verified = withContext(Dispatchers.IO) {
            runCatching { authClient.authenticatedUserId(token) }.getOrNull()
        }
        if (verified != null) {
            val cached = sessionVault.readVerifiedAccountId()
            if (cached != null && cached != verified) {
                withContext(Dispatchers.IO) { sessionVault.clear() }
                authSession = null
                return@LaunchedEffect
            }
            val bound = withContext(Dispatchers.IO) { sessionVault.bindVerifiedAccountId(token, verified) }
            if (bound) verifiedAccountId = verified
        }
    }
    if (verifiedAccountId == null) {
        Text("Для первого входа в архив нужен интернет. После подтверждения аккаунта архив будет доступен офлайн.",
            modifier = Modifier.padding(24.dp))
        return
    }
    val accountId = verifiedAccountId!!
    var remindersEnabled by remember(accountId) { mutableStateOf(ru.momento.app.data.RoomAlarmScheduler.isAccountEnabled(context)) }
    LaunchedEffect(accountId) {
        ru.momento.app.data.RoomAlarmScheduler.setAccount(context, accountId)
        remindersEnabled = ru.momento.app.data.RoomAlarmScheduler.isAccountEnabled(context)
    }
    val database = remember(context, accountId) { LocalMomentDatabase(context, accountId) }
    val spacesDatabase = remember(context, accountId) { LocalSpaceDatabase(context, accountId) }
    val scope = rememberCoroutineScope()
    val savedMoments = remember(accountId) { mutableStateListOf<LocalMoment>() }
    val uploadClient = remember { MomentoApiClient() }
    var uploadingMomentId by remember(accountId) { mutableStateOf<String?>(null) }
    val localSpaces = remember(accountId) { mutableStateListOf<LocalSpace>() }
    var publicRoomId by remember(accountId) { mutableStateOf<String?>(null) }
    var activeRoomId by rememberSaveable(accountId) { mutableStateOf<String?>(initialRoom) }
    var activeRoomLinks by remember(accountId) { mutableStateOf(emptyList<ru.momento.app.data.RoomMomentLink>()) }
    fun refreshRoomLinks(roomId: String) {
        scope.launch { activeRoomLinks = withContext(Dispatchers.IO) { spacesDatabase.momentsForRoom(roomId) } }
    }
    fun openRoom(roomId: String) {
        activeRoomId = roomId
        refreshRoomLinks(roomId)
        selectedTab = Tab.ROOM_DETAIL
    }
    // Resume operations that the owner already requested, including interrupted uploads.
    LaunchedEffect(database, spacesDatabase, accountId) {
        val queued = withContext(Dispatchers.IO) {
            val pendingRooms = database.roomSyncItems().map { it.second }.toSet()
            database.hasPendingUploads() || spacesDatabase.getSpaces().any {
                it.publicationRequested || (it.status == "PUBLISHED" && it.id in pendingRooms)
            }
        }
        if (queued) ru.momento.app.data.MomentUploadWorker.enqueue(context, accountId)
    }
    LaunchedEffect(database) {
        val records = withContext(Dispatchers.IO) { database.recoverUnindexedCaptures(context); database.getMoments() }
        savedMoments.clear()
        savedMoments.addAll(records)
    }
    LaunchedEffect(spacesDatabase) {
        val rooms = withContext(Dispatchers.IO) { spacesDatabase.getSpaces() }
        localSpaces.clear()
        localSpaces.addAll(rooms)
        if (activeRoomId == null || rooms.none { it.id == activeRoomId }) {
            activeRoomId = rooms.firstOrNull { it.status != "COMPLETED" && it.status != "DELETED" }?.id
        }
        ru.momento.app.data.RoomAlarmScheduler.reconcile(
            context, rooms.filter { it.status != "COMPLETED" && it.status != "DELETED" }.map { it.id }.toSet()
        )
    }
    // Refresh the visible archive and room status after WorkManager updates SQLite.
    LaunchedEffect(database, spacesDatabase, selectedTab) {
        if (selectedTab !in setOf(Tab.ARCHIVE, Tab.ROOM_DETAIL, Tab.MINE)) return@LaunchedEffect
        while (true) {
            delay(2_000L)
            val (latest, rooms) = withContext(Dispatchers.IO) { database.getMoments() to spacesDatabase.getSpaces() }
            if (rooms != localSpaces.toList()) {
                localSpaces.clear()
                localSpaces.addAll(rooms)
            }
            if (latest != savedMoments.toList()) {
                savedMoments.clear()
                savedMoments.addAll(latest)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text("MOMENTO", fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            val mainTabs = listOf(Tab.RECORD, Tab.SPACES, Tab.SUBSCRIPTIONS, Tab.PROFILE)
            MomentoNavigationBar(mainTabs.indexOf(selectedTab).coerceAtLeast(0)) { index ->
                selectedTab = mainTabs[index]
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            archiveError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(12.dp))
            }
            when (selectedTab) {
            Tab.SPACES -> LiveHomeScreen(
                rooms = localSpaces,
                activeRoomId = activeRoomId,
                onRecord = { selectedTab = Tab.RECORD },
                onCreate = { selectedTab = Tab.CREATE },
                onMine = { selectedTab = Tab.MINE },
                onOpen = ::openRoom
            )
            Tab.SUBSCRIPTIONS -> {
                val opened = publicRoomId
                if (opened == null) SubscriptionsScreen(authSession!!.accessToken, uploadClient,
                    onOpenRoom = { publicRoomId = it })
                else PublicRoomScreen(authSession!!.accessToken, opened, uploadClient,
                    onBack = { publicRoomId = null })
            }
            Tab.MINE -> MyRoomsScreen(localSpaces, onCreate = { selectedTab = Tab.CREATE }, onOpen = ::openRoom)
            Tab.SEARCH -> RoomSearchScreen(localSpaces, onOpen = ::openRoom)
            Tab.CREATE -> CreateRoomScreen(
                onSave = { name, description, visibility, topic ->
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) { spacesDatabase.createRoom(name, description, visibility, topic) }
                        }
                        result.onSuccess {
                            localSpaces.add(0, it)
                            activeRoomId = it.id
                            archiveError = null
                            selectedTab = Tab.MINE
                        }.onFailure { archiveError = "Не удалось сохранить комнату: ${it.message ?: "ошибка базы данных"}" }
                    }
                },
                onCancel = { selectedTab = Tab.SPACES }
            )
            Tab.ROOM_DETAIL -> {
                val room = localSpaces.firstOrNull { it.id == activeRoomId }
                if (room == null) Text("Комната не найдена") else RoomMomentsScreen(
                    room, savedMoments, activeRoomLinks,
                    onRemove = { id -> scope.launch {
                        withContext(Dispatchers.IO) { spacesDatabase.removeMoment(room.id, id) }; refreshRoomLinks(room.id)
                    } },
                    onSeries = { id, series -> scope.launch {
                        withContext(Dispatchers.IO) { spacesDatabase.setSeries(room.id, id, series) }; refreshRoomLinks(room.id)
                    } },
                    onPlay = { selectedTab = Tab.ROOM_PLAYER },
                    onRecord = { selectedTab = Tab.RECORD },
                    onPublish = {
                        scope.launch {
                            val result = runCatching { withContext(Dispatchers.IO) {
                                val links = spacesDatabase.momentsForRoom(room.id)
                                val moments = database.getMoments().associateBy { it.id }
                                val clips = links.map { checkNotNull(moments[it.momentId]) { "Видео отсутствует на устройстве" } }
                                spacesDatabase.requestPublication(room.id)
                                for (clip in clips) {
                                    if (clip.uploadState in setOf(LocalMomentDatabase.UPLOAD_FAILED, LocalMomentDatabase.UPLOAD_LOCAL_ONLY)) {
                                        database.queueUpload(clip.id)
                                    }
                                }
                            } }
                            result.onSuccess {
                                localSpaces.clear(); localSpaces.addAll(withContext(Dispatchers.IO) { spacesDatabase.getSpaces() })
                                ru.momento.app.data.MomentUploadWorker.enqueue(context,accountId)
                                archiveError = "Публикация поставлена в очередь. Локальные файлы сохранены."
                            }.onFailure { archiveError = "Не удалось поставить в очередь: ${it.message}" }
                        }
                    },
                    onBack = { selectedTab = Tab.MINE }
                )
            }
            Tab.ROOM_PLAYER -> {
                val room = localSpaces.firstOrNull { it.id == activeRoomId }
                if (room == null) Text("Комната не найдена") else RoomVlogPlayer(
                    room, activeRoomLinks.mapNotNull { link -> savedMoments.firstOrNull { it.id == link.momentId } },
                    onBack = { selectedTab = Tab.ROOM_DETAIL }
                )
            }
            Tab.ARCHIVE -> ArchiveScreen(PaddingValues(0.dp), savedMoments, uploadingMomentId) { moment ->
                if (uploadingMomentId != null || authSession?.accessToken.isNullOrBlank()) return@ArchiveScreen
                uploadingMomentId = moment.id
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) { database.queueUpload(moment.id) }
                        // A single WorkManager owner handles all network uploads.
                        // UI must never race it with a second direct HTTP request.
                        ru.momento.app.data.MomentUploadWorker.enqueue(context, accountId)
                    }
                    archiveError = if (result.isFailure) "Не удалось поставить момент в очередь." else null
                    savedMoments.clear()
                    savedMoments.addAll(withContext(Dispatchers.IO) { database.recoverUnindexedCaptures(context); database.getMoments() })
                    uploadingMomentId = null
                }
            }
            Tab.CALENDAR -> LifeCalendarScreen(savedMoments)
            Tab.CAPSULES -> CapsulesScreen(authSession?.accessToken.orEmpty(), remember { ru.momento.app.network.MomentoApiClient() })
            Tab.RECORD -> CameraScreen(
                verifiedOwnerId = accountId,
                roomNames = localSpaces.map { it.id to it.name },
                selectedRoomId = activeRoomId,
                onRoomSelected = { activeRoomId = it },
                onSaved = { file, durationMillis ->
                    scope.launch {
                        val record = runCatching {
                            withContext(Dispatchers.IO) { database.insertMoment(file, durationMillis) }
                        }.getOrElse {
                            archiveError = "Видео осталось на устройстве, но не удалось добавить его в архив. Перезапусти приложение."
                            return@launch
                        }
                        archiveError = null
                        savedMoments.add(0, record)
                        if (activeRoomId != null) {
                            val roomId = activeRoomId!!
                            val added = runCatching { withContext(Dispatchers.IO) { spacesDatabase.addMoment(roomId, record.id) } }
                            added.onFailure { archiveError = "Видео сохранено, но не добавлено в комнату" }
                            // A published public room is an ongoing vlog: newly captured moments
                            // join its durable upload queue without another publish button.
                            val currentRoom = localSpaces.firstOrNull { it.id == roomId }
                            if (added.isSuccess && currentRoom?.visibility == "PUBLIC" && currentRoom.status == "PUBLISHED") {
                                val queued = runCatching { withContext(Dispatchers.IO) {
                                    database.markForRoomSync(record.id, roomId)
                                    database.queueUpload(record.id)
                                } }.isSuccess
                                if (queued) ru.momento.app.data.MomentUploadWorker.enqueue(context, accountId)
                                else archiveError = "Момент сохранён локально, но не поставлен в очередь."
                            }
                            refreshRoomLinks(roomId)
                            selectedTab = Tab.ROOM_DETAIL
                        } else selectedTab = Tab.ARCHIVE
                    }
                },
                onClose = { selectedTab = if (activeRoomId != null) Tab.ROOM_DETAIL else Tab.PROFILE }
            )
            Tab.PROFILE -> androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(MomentoTokens.screenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { MomentoScreenHeading("Профиль", "Личные моменты и настройки") }
                item {
                    VideoAvatarSection(savedMoments)
                }
                item { ProfileAuthSection(
                    accessToken = authSession?.accessToken.orEmpty(),
                    client = authClient,
                    busy = logoutBusy,
                    onLogout = {
                        logoutBusy = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { authClient.logout(authSession?.refreshToken.orEmpty()) }
                            }
                            runCatching {
                                CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
                            }
                            if (ru.momento.app.BuildConfig.VK_CLIENT_ID.isNotBlank()) {
                                runCatching {
                                    VKID.instance.logout(object : VKIDLogoutCallback {
                                        override fun onSuccess() = Unit
                                        override fun onFail(fail: VKIDLogoutFail) = Unit
                                    })
                                }
                            }
                            withContext(Dispatchers.IO) { sessionVault.clear() }
                            ru.momento.app.data.RoomAlarmScheduler.disableAll(context)
                            authSession = null
                            logoutBusy = false
                        }
                    }
                ) }
                item { MomentoReminderPolicyCard(
                    enabled = remindersEnabled,
                    onToggle = { enabled ->
                        if (enabled) {
                            ru.momento.app.data.RoomAlarmScheduler.setEnabled(context, true)
                            remindersEnabled = true
                            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            ru.momento.app.data.RoomAlarmScheduler.setEnabled(context, false)
                            remindersEnabled = false
                        }
                    },
                    permissionGranted = android.os.Build.VERSION.SDK_INT < 33 ||
                        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) }
                item { MomentoSectionTitle("Мои материалы") }
                item { Button(onClick = { selectedTab = Tab.ARCHIVE }) { Text("Личный архив моментов") } }
                item { Button(onClick = { selectedTab = Tab.CALENDAR }) { Text("Календарь жизни") } }
                item { Button(onClick = { selectedTab = Tab.CAPSULES }) { Text("Капсулы времени") } }
                item { MomentoPrimaryAction("Записать личный момент", onClick = { activeRoomId = null; selectedTab = Tab.RECORD }) }
                /* Button(onClick = { activeRoomId = null; selectedTab = Tab.RECORD }) { Text("Записать личный момент") }
                Button(onClick = { selectedTab = Tab.SPACES }) { Text("Вернуться к комнатам") } */
            }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    padding: PaddingValues,
    moments: List<LocalMoment>,
    roomCount: Int,
    onRecordClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onRoomsClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Твоя жизнь — в моментах", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Сохраняй короткие видео и делись ими с близкими.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onRecordClick() },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Поймай момент", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("Всего несколько секунд — воспоминание останется с тобой.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.size(54.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
        item { SectionTitle("Недавние моменты", "Всего сохранено: ${moments.size}") }
        if (moments.isEmpty()) {
            item { EmptyCard("Твои первые моменты появятся здесь") }
        } else {
            items(moments.take(3), key = { "home_${it.id}" }) { moment ->
                Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onArchiveClick)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(SimpleDateFormat("d MMMM yyyy · HH:mm", Locale.forLanguageTag("ru-RU"))
                            .format(Date(moment.capturedAtMillis)), fontWeight = FontWeight.SemiBold)
                        Text("${moment.durationMillis / 1000.0} сек · Открыть в архиве",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onRoomsClick)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Твои комнаты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (roomCount == 0) "Пока нет комнат · Создать комнату"
                         else "Комнат на устройстве: $roomCount · Открыть",
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ArchiveScreen(
    padding: PaddingValues,
    moments: List<LocalMoment>,
    uploadingMomentId: String?,
    onUpload: (LocalMoment) -> Unit
) {
    if (moments.isEmpty()) {
        SimpleScreen(padding, "Мой архив", "Здесь будут храниться твои моменты по датам. Запиши первый момент — он появится здесь.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Мой архив", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        items(moments, key = { it.id }) { moment ->
            val file = moment.file
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        SimpleDateFormat("d MMMM yyyy · HH:mm", Locale.forLanguageTag("ru-RU")).format(Date(moment.capturedAtMillis)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    AndroidView(
                        factory = { context -> VideoView(context).apply {
                            setVideoURI(Uri.fromFile(file))
                            setOnPreparedListener { player -> player.isLooping = true; start() }
                        } },
                        modifier = Modifier.fillMaxWidth().height(220.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Личный архив · ${moment.durationMillis / 1000.0} с · ${moment.byteSize / 1024} КБ", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    if (moment.uploadState == LocalMomentDatabase.UPLOAD_UPLOADED_PRIVATE) {
                        Text("Сохранено в личном облачном архиве", color = MaterialTheme.colorScheme.primary)
                    } else {
                        Button(
                            onClick = { onUpload(moment) },
                            enabled = uploadingMomentId == null && moment.file.isFile
                        ) {
                            Text(when {
                                uploadingMomentId == moment.id -> "Добавление в очередь…"
                                moment.uploadState == LocalMomentDatabase.UPLOAD_QUEUED -> "В очереди · повторить"
                                moment.uploadState == LocalMomentDatabase.UPLOAD_UPLOADING -> "Отправляется · повторить позже"
                                moment.uploadState == LocalMomentDatabase.UPLOAD_FAILED -> "Повторить загрузку"
                                else -> "Загрузить в личный архив"
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpacesScreen(
    padding: PaddingValues,
    spaces: List<LocalSpace>,
    onCreateSpace: (String) -> Unit
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var roomName by rememberSaveable { mutableStateOf("") }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Новая комната") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Создай пространство для общих коротких видео.")
                    OutlinedTextField(
                        value = roomName,
                        onValueChange = { roomName = it.take(60) },
                        label = { Text("Название комнаты") },
                        singleLine = true,
                        supportingText = { Text("До 60 символов") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = roomName.trim().isNotEmpty(),
                    onClick = {
                        onCreateSpace(roomName)
                        roomName = ""
                        showCreateDialog = false
                    }
                ) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Отмена") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("Комнаты", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Общие моменты с теми, кто тебе важен.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Button(onClick = { showCreateDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Создать комнату")
        }
        Spacer(Modifier.height(20.dp))
        Text("Мои комнаты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (spaces.isEmpty()) {
            EmptyCard("Здесь появятся комнаты, в которых ты участвуешь. Создай первую — для семьи, друзей или близких.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(spaces, key = { it.id }) { space ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(space.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Создана ${SimpleDateFormat("d MMM yyyy · HH:mm", Locale.forLanguageTag("ru-RU")).format(Date(space.createdAtMillis))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Сохранена на этом устройстве · синхронизация ещё не подключена", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Приглашения и общая лента появятся после подключения сервера. Сейчас комнаты сохраняются локально — приложение не создаёт неработающие ссылки или фиктивные приглашения.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SimpleScreen(padding: PaddingValues, title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(Modifier.fillMaxWidth().padding(22.dp), contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
