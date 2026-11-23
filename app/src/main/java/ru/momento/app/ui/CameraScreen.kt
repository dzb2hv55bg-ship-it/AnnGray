package ru.momento.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import ru.momento.app.data.LocalMomentDatabase
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    var recordAudio by rememberSaveable { mutableStateOf(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(0L) }
    var activeOutput by remember { mutableStateOf<File?>(null) }
    val cancelledPaths = remember { mutableSetOf<String>() }
    var secondsLeft by remember { mutableIntStateOf(5) }
    var secondsElapsed by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        permissionDenied = !granted
    }

    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        recordAudio = granted
        if (!granted) errorMessage = "Микрофон отключён. Можно записать видео без звука."
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val latestOutput = rememberUpdatedState(activeOutput)
    val latestRecording = rememberUpdatedState(recording)
    val latestCameraProvider = rememberUpdatedState(cameraProvider)
    DisposableEffect(lifecycleOwner) {
        onDispose {
            latestOutput.value?.let { cancelledPaths.add(it.absolutePath) }
            latestRecording.value?.stop()
            latestCameraProvider.value?.unbindAll()
        }
    }

    // Only the finalized event can commit a moment. A timer never marks recording successful.
    LaunchedEffect(isRecording, startedAt) {
        if (isRecording) {
            while (isRecording) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                secondsElapsed = (elapsed / 1_000).toInt().coerceAtMost(5)
                secondsLeft = (5 - secondsElapsed).coerceAtLeast(0)
                if (elapsed >= 4_800L) {
                    recording?.stop()
                    break
                }
                delay(100)
            }
        }
    }

    var roomPickerOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onClose, enabled = !isBusy) { Text("Закрыть") }
            Text(if (isRecording) "Запись: ${secondsLeft}с" else "Момент 2–5 секунд", color = Color.White)
        }

        if (!isBusy && roomNames.isNotEmpty()) {
            val chosen = roomNames.firstOrNull { it.first == selectedRoomId }?.second ?: "Выбрать комнату"
            OutlinedButton(onClick = { roomPickerOpen = !roomPickerOpen }) {
                Text("Комната: $chosen ▾")
            }
            if (roomPickerOpen) {
                roomNames.forEach { (id, title) ->
                    OutlinedButton(onClick = { onRoomSelected(id); roomPickerOpen = false }) {
                        Text(title)
                    }
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clip(MaterialTheme.shapes.extraLarge)) {
            when {
                hasCameraPermission -> {
                    AndroidView(
                        factory = { viewContext ->
                            PreviewView(viewContext).also { previewView ->
                                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                                val future = ProcessCameraProvider.getInstance(viewContext)
                                future.addListener({
                                    try {
                                        val provider = future.get()
                                        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                                        val recorder = Recorder.Builder()
                                            .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                                            .build()
                                        val capture = VideoCapture.withOutput(recorder)
                                        provider.unbindAll()
                                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                                        videoCapture = capture
                                        cameraProvider = provider
                                    } catch (exception: Exception) {
                                        errorMessage = "Камера недоступна: ${exception.localizedMessage ?: "ошибка"}"
                                    }
                                }, mainExecutor)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (permissionDenied) "Для записи MOMENTO нужен доступ к камере." else "Запрашиваем доступ к камере…", color = Color.White)
                }
            }
            if (isRecording) {
                Box(Modifier.align(Alignment.TopCenter).padding(16.dp).clip(CircleShape).background(Color.Red).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text("● REC  ${secondsLeft}", color = Color.White)
                }
            }
        }

        errorMessage?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(8.dp)) }

        MomentoRecordingStatus(isRecording, secondsElapsed)
        OutlinedButton(
            onClick = {
                errorMessage = null
                if (recordAudio) recordAudio = false
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    recordAudio = true
                } else microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            enabled = !isBusy
        ) { Text(if (recordAudio) "Выключить микрофон" else "Включить микрофон") }
        Text(if (isRecording) "Не закрывай камеру до сохранения" else "Только камера · без загрузки готовых видео",
            color = Color.LightGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            Button(
                onClick = {
                    if (isBusy) return@Button
                    val capture = videoCapture ?: return@Button
                    val directory = File(context.filesDir, "moments_${LocalMomentDatabase.databaseNameForAccount(verifiedOwnerId).removeSuffix(".db")}").apply { mkdirs() }
                    if (!directory.isDirectory || StatFs(directory.absolutePath).availableBytes < 30L * 1024 * 1024) {
                        errorMessage = "Недостаточно места для записи момента."
                        return@Button
                    }
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                    val output = File(directory, "moment_$stamp.mp4")
                    // Leave room for the last encoded frame and container timestamps.
                    val options = FileOutputOptions.Builder(output)
                        .setDurationLimitMillis(LocalMomentDatabase.MAX_DURATION_MS - 100)
                        .build()
                    errorMessage = null
                    secondsElapsed = 0
                    secondsLeft = 5
                    isBusy = true
                    activeOutput = output
                    try {
                    val pending = capture.output.prepareRecording(context, options)
                    val audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (recordAudio && !audioGranted) {
                        recordAudio = false
                        errorMessage = "Микрофон отключён. Видео будет записано без звука."
                    }
                    // Permission is checked here because it can change while the screen is open.
                    @SuppressLint("MissingPermission")
                    val prepared = if (recordAudio && audioGranted) pending.withAudioEnabled() else pending
                    recording = prepared.start(mainExecutor) { event ->
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
                                } else if ((!event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) &&
                                    output.isFile && output.length() > 0L) {
                                    val duration = LocalMomentDatabase.readCaptureDuration(output)
                                    if (duration != null) {
                                        // Keep the only successful file until the caller has indexed it.
                                        try { onSaved(output, duration) }
                                        catch (e: Exception) { errorMessage = "Момент сохранён на устройстве, но не добавлен в очередь. Файл сохранён: ${output.name}" }
                                    } else {
                                        output.delete()
                                        errorMessage = "Не удалось прочитать видео или его длительность вне диапазона 2–5 секунд. Попробуй записать ещё раз."
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
                },
                enabled = hasCameraPermission && videoCapture != null && !isBusy,
                modifier = Modifier.padding(bottom = 20.dp).size(86.dp).semantics {
                    contentDescription = "Начать запись"
                },
                shape = CircleShape,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (isBusy) 32.dp else 54.dp)
                        .background(if (isBusy) Color(0xFF8BB9FF) else Color.White,
                            if (isBusy) MaterialTheme.shapes.small else CircleShape))
                }
            }
            if (isRecording && secondsElapsed >= 2) {
                OutlinedButton(onClick = { recording?.stop() }, modifier = Modifier.padding(bottom = 8.dp)) { Text("Завершить момент") }
            }
            if (isBusy) {
                OutlinedButton(onClick = {
                    // CameraX finalize may still arrive; discard its output, never publish cancellation.
                    activeOutput?.let { cancelled ->
                        // Mark cancellation via a stable path; finalize checks this flag.
                        cancelledPaths.add(cancelled.absolutePath)
                    }
                    recording?.stop()
                }, modifier = Modifier.padding(bottom = 8.dp)) { Text("Отменить запись") }
            }
    }
}
