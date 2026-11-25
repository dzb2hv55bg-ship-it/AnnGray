package ru.momento.app.ui

import android.content.ClipData
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.momento.app.data.LocalMoment
import ru.momento.app.data.LocalMomentDatabase
import ru.momento.app.network.MomentoApiClient
import ru.momento.app.network.TimelineCursor
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private enum class FilmPeriod(val label: String) {
    DAY("День"), WEEK("Неделя"), MONTH("Месяц"), YEAR("Год"), ALL("Всё время");

    fun since(): Long {
        if (this == ALL) return Long.MIN_VALUE
        return Calendar.getInstance().apply {
            when (this@FilmPeriod) {
                DAY -> add(Calendar.DAY_OF_YEAR, -1)
                WEEK -> add(Calendar.WEEK_OF_YEAR, -1)
                MONTH -> add(Calendar.MONTH, -1)
                YEAR -> add(Calendar.YEAR, -1)
                ALL -> Unit
            }
        }.timeInMillis
    }
}

private data class FilmClip(
    val id: String,
    val durationMillis: Long,
    val label: String,
    val capturedAtMillis: Long = 0,
    val file: File? = null
)

@Composable
internal fun LifeFilmScreen(accountId: String, moments: List<LocalMoment>, onBack: () -> Unit) {
    val context = LocalContext.current
    val directory = remember(accountId) {
        File(context.filesDir, "moments_${LocalMomentDatabase.databaseNameForAccount(accountId).removeSuffix(".db")}")
    }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val clips = moments.sortedWith(compareBy<LocalMoment> { it.capturedAtMillis }.thenBy { it.id }).map {
        FilmClip(it.id, it.durationMillis, format.format(Date(it.capturedAtMillis)), it.capturedAtMillis, it.file)
    }
    FilmScreen("Жизнь в секундах", clips, directory, null, onBack)
}

@Composable
internal fun EventFilmScreen(token: String, roomId: String, api: MomentoApiClient, onBack: () -> Unit) {
    var clips by remember(roomId, token) { mutableStateOf<List<FilmClip>?>(null) }
    var error by remember(roomId, token) { mutableStateOf<String?>(null) }
    LaunchedEffect(roomId, token) {
        try {
            val result = mutableListOf<FilmClip>()
            var cursor: TimelineCursor? = null
            do {
                val page = api.getSpaceTimeline(token, roomId, cursor, limit = 50)
                result.addAll(page.items.map { FilmClip(it.id, it.durationMs.toLong(), "${it.capturedAt} · ${it.authorDisplayName}") })
                cursor = page.nextCursor
            } while (page.items.size == 50 && cursor != null)
            clips = result.asReversed()
            while (true) {
                delay(15_000)
                api.specialRoomMembers(token, roomId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            clips = null
            error = "История события недоступна."
        }
    }
    val source = clips
    if (source == null) {
        BackHandler(onBack = onBack)
        Column(Modifier.padding(MomentoTokens.screenPadding)) {
            TextButton(onClick = onBack) { Text("Назад") }
            Text(error ?: "Загрузка истории события")
        }
    } else FilmScreen("Фильм события", source, null, { file, id ->
        api.downloadMomentVideo(token, id, file)
    }, onBack)
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun FilmScreen(
    title: String,
    sources: List<FilmClip>,
    captureDirectory: File?,
    download: (suspend (File, String) -> File)?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf(FilmPeriod.MONTH) }
    val cutoff = remember(period) { period.since() }
    val clips = if (captureDirectory == null) sources else sources.filter { it.capturedAtMillis >= cutoff }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var preparation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var export by remember { mutableStateOf<Transformer?>(null) }
    var output by remember { mutableStateOf<File?>(null) }
    var pending by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }

    fun checkedFile(clip: FilmClip): File {
        val file = requireNotNull(clip.file).canonicalFile
        require(file.parentFile == captureDirectory?.canonicalFile && file.isFile && file.length() > 0)
        return file
    }

    DisposableEffect(Unit) {
        onDispose {
            preparation?.cancel()
            export?.cancel()
            downloaded?.deleteRecursively()
            pending?.delete()
            output?.delete()
        }
    }
    LaunchedEffect(export) {
        val active = export ?: return@LaunchedEffect
        val holder = ProgressHolder()
        while (true) {
            progress = if (active.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null
            delay(250)
        }
    }

    val cover by produceState<android.graphics.Bitmap?>(null, clips.firstOrNull()?.id) {
        value = null
        val clip = clips.firstOrNull()?.takeIf { it.file != null } ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(checkedFile(clip).path)
                    if (android.os.Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)
                    } else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val file = output
        if (uri != null && file != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openOutputStream(uri)).use { target ->
                            file.inputStream().use { it.copyTo(target) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = "Не удалось сохранить фильм."
                } finally {
                    busy = false
                }
            }
        }
    }

    BackHandler {
        if (preview) preview = false else if (!busy) onBack()
    }
    if (preview) {
        val file = output ?: return
        val player = remember(file) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { preview = false }) { Text("К фильму") }
            AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MomentoTokens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { TextButton(onClick = onBack, enabled = !busy) { Text("Назад") } }
        item {
            MomentoScreenHeading(title, if (download == null) "Фильм из твоих моментов" else "Общая история")
            if (download == null && clips.isNotEmpty()) {
                Text("Фильм собирается по порядку записи. Исходные моменты останутся в архиве.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        cover?.let { bitmap ->
            item { Image(bitmap.asImageBitmap(), "Кадр первого момента", Modifier.fillMaxWidth().height(180.dp)) }
        }
        item {
            var choosing by remember { mutableStateOf(false) }
            if (captureDirectory != null) Box {
                OutlinedButton(onClick = { choosing = true }, enabled = export == null && !busy) { Text(period.label) }
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    FilmPeriod.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            period = option
                            choosing = false
                            output?.delete()
                            output = null
                            error = null
                        })
                    }
                }
            }
            Text("Моментов: ${clips.size} · ${(clips.sumOf { it.durationMillis } + 500) / 1000} с")
        }
        item {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when {
                preparation?.isActive == true || export != null -> {
                    val percent = progress
                    if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("$percent %")
                    }
                    TextButton(onClick = {
                        preparation?.cancel()
                        preparation = null
                        export?.cancel()
                        export = null
                        downloaded?.deleteRecursively()
                        downloaded = null
                        pending?.delete()
                        pending = null
                        progress = null
                    }) { Text("Отменить") }
                }
                clips.isEmpty() -> Text(if (sources.isEmpty() && download == null)
                    "Сначала запиши момент в камере. Затем вернись сюда, чтобы собрать фильм."
                else "За этот период в архиве нет моментов.")
                else -> Button(enabled = !busy, onClick = {
                    error = null
                    output?.delete()
                    output = null
                    preparation = scope.launch {
                    try {
                        val files = if (download == null) withContext(Dispatchers.IO) {
                            clips.map(::checkedFile)
                        } else {
                            val directory = File(context.cacheDir, "momento-event-${java.util.UUID.randomUUID()}")
                            check(directory.mkdir())
                            downloaded = directory
                            clips.mapIndexed { index, clip ->
                                progress = (index * 100 / clips.size)
                                download(File(directory, "${clip.id}.mp4"), clip.id)
                            }
                        }
                        val items = files.map { file ->
                            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file))).build()
                        }
                        val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()
                        val file = File.createTempFile("momento-film-", ".mp4", context.cacheDir)
                        pending = file
                        val transformer = Transformer.Builder(context)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    export = null
                                    pending = null
                                    downloaded?.deleteRecursively()
                                    downloaded = null
                                    output = file
                                }

                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    export = null
                                    pending = null
                                    downloaded?.deleteRecursively()
                                    downloaded = null
                                    file.delete()
                                    error = "Экспорт остановился. Проверь исходные записи и свободное место."
                                }
                            }).build()
                        progress = null
                        export = transformer
                        transformer.start(composition, file.path)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        export?.cancel()
                        export = null
                        pending?.delete()
                        pending = null
                        downloaded?.deleteRecursively()
                        downloaded = null
                        error = "Не удалось начать экспорт. Проверь исходные записи и свободное место."
                    }
                    }
                }) { Text("Создать фильм") }
            }
        }
        output?.let { file ->
            item {
                Button(onClick = { preview = true }, enabled = !busy) { Text("Смотреть фильм") }
                if (download == null) OutlinedButton(onClick = { save.launch("MOMENTO.mp4") }, enabled = !busy) { Text("Сохранить") }
                if (download == null) OutlinedButton(onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val shared = withContext(Dispatchers.IO) {
                                val directory = File(context.cacheDir, "shared-films").apply { mkdirs() }
                                directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000L }
                                    ?.forEach { it.delete() }
                                File.createTempFile("MOMENTO-", ".mp4", directory).also {
                                    try { file.copyTo(it, overwrite = true) } catch (failure: Exception) {
                                        it.delete()
                                        throw failure
                                    }
                                }
                            }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.films", shared)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri("MOMENTO", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Поделиться фильмом"))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "Не удалось подготовить фильм для отправки."
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy) { Text("Поделиться") }
            }
        }
        items(clips, key = { it.id }) { clip ->
            Text(clip.label)
        }
    }
}
