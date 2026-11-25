package ru.momento.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.momento.app.data.LocalMoment
import ru.momento.app.data.LocalMomentDatabase
import ru.momento.app.data.LocalMomentDatabase.Companion.LOCAL_PREVIEW_OWNER
import ru.momento.app.network.MomentoApiClient

private enum class LocalPage { CAMERA, ROOMS, SUBSCRIPTIONS, PROFILE, ARCHIVE, FILM }

@Composable
internal fun LocalPreviewScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(LocalPage.PROFILE) }
    var moments by remember { mutableStateOf<List<LocalMoment>?>(null) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }

    LaunchedEffect(loadAttempt) {
        loadFailed = false
        try {
            moments = withContext(Dispatchers.IO) {
                LocalMomentDatabase(context, LOCAL_PREVIEW_OWNER).use { database ->
                    database.recoverUnindexedCaptures(context)
                    database.getMoments()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }

    BackHandler(enabled = page != LocalPage.ROOMS) {
        if (!saving) {
            if (page == LocalPage.PROFILE) onExit()
            else page = LocalPage.PROFILE
        }
    }

    Scaffold(bottomBar = {
        if (page != LocalPage.CAMERA) {
            val tabs = listOf(LocalPage.CAMERA, LocalPage.ROOMS, LocalPage.SUBSCRIPTIONS, LocalPage.PROFILE)
            MomentoNavigationBar(tabs.indexOf(page).takeIf { it >= 0 } ?: 3) { page = tabs[it] }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (saving) LinearProgressIndicator()
            if (saveError) Text(
                "Видео осталось на устройстве. Открой архив, чтобы попробовать восстановить запись.",
                Modifier.padding(horizontal = MomentoTokens.screenPadding),
                color = MaterialTheme.colorScheme.error
            )
            val records = moments
            when (page) {
                LocalPage.CAMERA -> if (records != null && !saving) CameraScreen(
                    verifiedOwnerId = LOCAL_PREVIEW_OWNER,
                    onSaved = { file, duration ->
                        saving = true
                        saveError = false
                        scope.launch {
                            try {
                                moments = withContext(Dispatchers.IO) {
                                    LocalMomentDatabase(context, LOCAL_PREVIEW_OWNER).use { database ->
                                        database.insertMoment(file, duration)
                                        database.getMoments()
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                saveError = true
                                loadAttempt++
                            } finally {
                                saving = false
                            }
                            page = LocalPage.ARCHIVE
                        }
                    },
                    onClose = { page = LocalPage.PROFILE }
                ) else if (!saving) LocalArchiveStatus(loadFailed) { loadAttempt++ }
                LocalPage.ROOMS -> SpecialRoomsScreen(
                    token = "", accountId = LOCAL_PREVIEW_OWNER, api = remember { MomentoApiClient("") },
                    onRecord = { _, _ -> }, onBack = { page = LocalPage.PROFILE }, previewOnly = true
                )
                LocalPage.SUBSCRIPTIONS -> Column(Modifier.padding(MomentoTokens.screenPadding)) {
                    MomentoStatePanel("Подписки", "Для просмотра ленты авторов и комнат потребуется подключение к MOMENTO.",
                        "К экрану входа", onExit)
                }
                LocalPage.PROFILE -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MomentoTokens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MomentoProfileHeader("Локальный профиль",
                        "Здесь твои записи остаются на устройстве. Открой этот режим снова, чтобы вернуться к ним.")
                    MomentoRoomTile("Мой архив", "Открой записи и пересмотри любой момент.",
                        "Хранится на устройстве", onClick = { page = LocalPage.ARCHIVE }, icon = Icons.Default.Folder)
                    MomentoRoomTile("Жизнь в секундах", "Выбери период и собери один фильм из коротких записей.",
                        "Экспорт в MP4", onClick = { page = LocalPage.FILM }, icon = Icons.Default.PlayArrow)
                    MomentoPrimaryAction("Записать момент", onClick = { page = LocalPage.CAMERA })
                    TextButton(onClick = onExit) { Text("Выйти из локального режима") }
                }
                LocalPage.ARCHIVE -> if (records != null) {
                    ArchiveScreen(PaddingValues(0.dp), records, uploadingMomentId = null,
                        onRecord = { page = LocalPage.CAMERA })
                } else LocalArchiveStatus(loadFailed) { loadAttempt++ }
                LocalPage.FILM -> if (records != null) {
                    LifeFilmScreen(LOCAL_PREVIEW_OWNER, records) { page = LocalPage.PROFILE }
                } else LocalArchiveStatus(loadFailed) { loadAttempt++ }
            }
        }
    }
}

@Composable
private fun LocalArchiveStatus(failed: Boolean, retry: () -> Unit) {
    Column(Modifier.padding(MomentoTokens.screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (failed) MomentoStatePanel("Архив недоступен", "Не удалось открыть локальные записи.", "Повторить", retry, isError = true)
        else {
            LinearProgressIndicator()
            Text("Открываем локальный архив…")
        }
    }
}
