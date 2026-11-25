package ru.momento.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.os.StatFs
import kotlinx.coroutines.delay
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CameraScreen(
    roomNames: List<Pair<String, String>> = emptyList(),
    verifiedOwnerId: String,
    selectedRoomId: String? = null,
    onRoomSelected: (String) -> Unit = {},
    onSaved: (File, Long) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var frontFacing by rememberSaveable { mutableStateOf(false) }
    var canSwitch by remember { mutableStateOf(false) }
    val viewfinder = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(0L) }
    var activeOutput by remember { mutableStateOf<File?>(null) }
    val cancelledPaths = remember { mutableSetOf<String>() }
    var secondsElapsed by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        permissionDenied = !granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(lifecycleOwner, viewfinder, hasCameraPermission, frontFacing) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var boundPreview: Preview? = null
        var boundCapture: VideoCapture<Recorder>? = null
        videoCapture = null
        if (hasCameraPermission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed && lifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
                    try {
                        val ready = future.get()
                        provider = ready
                        val hasFront = ready.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                        val hasBack = ready.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                        canSwitch = hasFront && hasBack
                        check(hasFront || hasBack) { "На устройстве нет доступной камеры" }
                        val useFront = hasFront && (frontFacing || !hasBack)
                        val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                        val preview = Preview.Builder().build().also { it.surfaceProvider = viewfinder.surfaceProvider }
                        val recorder = Recorder.Builder()
                            .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                            .build()
                        val capture = VideoCapture.withOutput(recorder)
                        boundPreview = preview
                        boundCapture = capture
                        ready.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                        videoCapture = capture
                        errorMessage = null
                    } catch (_: Exception) {
                        errorMessage = "Не удалось открыть камеру. Попробуй другую камеру или открой экран заново."
                    }
                }
            }, mainExecutor)
        }
        onDispose {
            disposed = true
            activeOutput?.let { cancelledPaths.add(it.absolutePath) }
            recording?.stop()
            val preview = boundPreview
            val capture = boundCapture
            if (preview != null && capture != null) provider?.unbind(preview, capture)
        }
    }

    // Only the finalized event can commit a moment. A timer never marks recording successful.
    LaunchedEffect(isRecording, startedAt) {
        if (isRecording) {
            while (isRecording) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                secondsElapsed = (elapsed / 1_000).toInt().coerceAtMost(5)
                if (elapsed >= 4_800L) {
                    recording?.stop()
                    break
                }
                delay(100)
            }
        }
    }

    fun startRecording() {
        if (isBusy) return
        val capture = videoCapture ?: return
        val directory = File(context.filesDir, "moments_${ru.momento.app.data.LocalMomentDatabase.databaseNameForAccount(verifiedOwnerId).removeSuffix(".db")}").apply { mkdirs() }
        if (!directory.isDirectory || StatFs(directory.absolutePath).availableBytes < 30L * 1024 * 1024) {
            errorMessage = "Недостаточно места для записи момента."
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val output = File(directory, "moment_$stamp.mp4")
        val options = FileOutputOptions.Builder(output).build()
        errorMessage = null
        secondsElapsed = 0
        isBusy = true
        activeOutput = output
        try {
            recording = capture.output.prepareRecording(context, options).start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> { startedAt = SystemClock.elapsedRealtime(); isRecording = true }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        recording = null
                        isBusy = false
                        activeOutput = null
                        if (cancelledPaths.remove(output.absolutePath)) {
                            output.delete()
                            errorMessage = "Запись отменена"
                        } else if (!event.hasError() && output.exists() && output.length() > 0L) {
                            val retriever = MediaMetadataRetriever()
                            val duration = try {
                                retriever.setDataSource(output.absolutePath)
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                            } catch (_: Exception) {
                                null
                            } finally {
                                try { retriever.release() } catch (_: Exception) { }
                            }
                            if (duration != null && duration in 2_000L..5_000L) {
                                // Keep the only successful file until the caller has indexed it.
                                try { onSaved(output, duration) }
                                catch (e: Exception) { errorMessage = "Момент сохранён на устройстве, но не добавлен в очередь. Файл сохранён: ${output.name}" }
                            } else {
                                output.delete()
                                errorMessage = "Видео не прошло проверку длительности. Попробуй записать ещё раз."
                            }
                        } else {
                            output.delete()
                            errorMessage = "Не удалось сохранить видео. Попробуй ещё раз."
                        }
                    }
                }
            }
        } catch (e: Exception) {
            isBusy = false
            isRecording = false
            recording = null
            activeOutput = null
            output.delete()
            errorMessage = "Не удалось начать запись: ${e.localizedMessage ?: "ошибка камеры"}"
        }
    }

    fun cancelRecording() {
        activeOutput?.let { cancelledPaths.add(it.absolutePath) }
        recording?.stop()
    }

    var roomPickerOpen by remember { mutableStateOf(false) }
    val destination = roomNames.firstOrNull { it.first == selectedRoomId }?.second ?: "Личный момент"
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, enabled = !isBusy) { Icon(Icons.Default.Close, "Закрыть камеру") }
            Column {
                Text("Камера", style = MaterialTheme.typography.titleLarge)
                val lens = if (frontFacing) "Фронтальная" else "Задняя"
                Text(if (canSwitch) "$destination · $lens" else destination,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (roomNames.isNotEmpty()) {
            Box {
                OutlinedButton(onClick = { roomPickerOpen = true }, enabled = !isBusy) { Text("Выбрать комнату") }
                androidx.compose.material3.DropdownMenu(expanded = roomPickerOpen, onDismissRequest = { roomPickerOpen = false }) {
                    roomNames.forEach { (id, title) ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(title) }, onClick = {
                            onRoomSelected(id)
                            roomPickerOpen = false
                        })
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(32.dp)).background(Color(0xFF102139))
        ) {
            if (hasCameraPermission) {
                AndroidView(factory = { viewfinder }, modifier = Modifier.fillMaxSize())
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(if (permissionDenied) "Разреши доступ к камере, чтобы записать момент." else "Запрашиваем доступ к камере…",
                        color = Color.White)
                    if (permissionDenied) OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Запросить доступ", color = Color.White)
                    }
                }
            }
            Surface(
                Modifier.align(Alignment.TopCenter).padding(16.dp),
                shape = CircleShape, color = Color(0xCF132440), contentColor = Color.White
            ) {
                Text(
                    if (isRecording) "● Запись  0:0${secondsElapsed} / 0:05" else if (isBusy) "Сохраняем момент…" else "2–5 секунд",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (hasCameraPermission) {
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEC0B1529))))
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    if (isBusy) {
                        IconButton(onClick = ::cancelRecording) { Icon(Icons.Default.Close, "Отменить запись", tint = Color.White) }
                    } else Spacer(Modifier.size(48.dp))
                    val readyToStop = isRecording && secondsElapsed >= 2
                    val action = if (isBusy) "Завершить запись" else "Записать момент"
                    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { secondsElapsed / 5f }, modifier = Modifier.fillMaxSize(),
                            color = Color(0xFF81B7FF), trackColor = Color.White, strokeWidth = 4.dp
                        )
                        FilledIconButton(
                            onClick = { if (readyToStop) recording?.stop() else if (!isBusy) startRecording() },
                            enabled = readyToStop || (!isBusy && videoCapture != null),
                            modifier = Modifier.size(72.dp).semantics { contentDescription = action },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (isBusy) Color.White else Color(0xFF0068E8),
                                contentColor = if (isBusy) Color(0xFFBA2334) else Color.White,
                                disabledContainerColor = Color(0xFFD9E6F7), disabledContentColor = Color(0xFF782635)
                            )
                        ) { Icon(if (isBusy) Icons.Default.Stop else Icons.Default.Videocam, null, Modifier.size(32.dp)) }
                    }
                    if (canSwitch) {
                        FilledIconButton(
                            onClick = {
                                videoCapture = null
                                errorMessage = null
                                frontFacing = !frontFacing
                            },
                            enabled = !isBusy,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xD42D4364), contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.Cameraswitch, if (frontFacing) "Переключить на заднюю камеру" else "Переключить на фронтальную камеру")
                        }
                    } else Spacer(Modifier.size(48.dp))
                }
            }
        }
        Text(
            errorMessage ?: when {
                !hasCameraPermission -> "Камера используется только для записи моментов."
                isRecording && secondsElapsed < 2 -> "Остановить запись можно после двух секунд."
                isRecording -> "Останови сейчас или дождись автоматического завершения."
                videoCapture == null -> "Подключаем камеру…"
                else -> "Видео появится в архиве. Звук не записывается."
            },
            Modifier.padding(bottom = 12.dp),
            color = if (errorMessage == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
