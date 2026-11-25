package ru.momento.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.yandex.authsdk.YandexAuthSdk
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthResult
import com.vk.id.VKID
import com.vk.id.auth.VKIDAuthCallback
import com.vk.id.VKIDAuthFail
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.momento.app.BuildConfig
import ru.momento.app.network.MomentoApiException
import ru.momento.app.network.MomentoAuthClient
import ru.momento.app.network.MomentoAuthSession
import ru.momento.app.network.MomentoSessionVault

@Composable
fun AuthScreen(
    vault: MomentoSessionVault,
    onAuthenticated: (MomentoAuthSession) -> Unit,
    onGuest: () -> Unit = {},
    onLocalPreview: () -> Unit
) {
    var contactType by rememberSaveable { mutableStateOf("EMAIL") }
    var contact by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var challengeId by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resendSeconds by remember { mutableStateOf(0) }
    val normalizedContact = if (contactType == "PHONE") {
        contact.filterNot { it.isWhitespace() || it in "()-" }
    } else {
        contact.trim()
    }
    val validContact = if (contactType == "EMAIL") {
        android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedContact).matches()
    } else {
        Regex("^\\+[1-9][0-9]{7,14}$").matches(normalizedContact)
    }
    LaunchedEffect(resendSeconds) {
        if (resendSeconds > 0) {
            delay(1000)
            resendSeconds--
        }
    }
    fun readableError(throwable: Throwable): String = when (throwable) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "Нет соединения с сервером. Проверь интернет и попробуй снова."
        is java.net.SocketTimeoutException -> "Сервер не ответил вовремя. Повтори попытку."
        is MomentoApiException -> when (throwable.statusCode) {
            400, 422 -> "Проверь адрес почты, номер телефона или введённый код."
            401 -> "Неверный или просроченный код. Запроси новый."
            429 -> "Слишком много попыток. Подожди перед повторной отправкой."
            in 500..599 -> "Сервис временно недоступен. Попробуй позже."
            else -> "Не удалось выполнить запрос (HTTP ${throwable.statusCode})."
        }
        else -> "Не удалось выполнить действие. Попробуй ещё раз."
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val client = remember { MomentoAuthClient(BuildConfig.MOMENTO_API_BASE_URL) }
    val apiConfigured = BuildConfig.MOMENTO_API_CONFIGURED
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val yandexSdk = remember(context) { YandexAuthSdk.create(YandexAuthOptions(context)) }
    var socialBusy by remember { mutableStateOf(false) }
    val canEdit = !busy && !socialBusy
    fun requestSignInCode() {
        if (!apiConfigured || busy || socialBusy || !validContact) return
        val channel = contactType
        val destination = normalizedContact
        busy = true
        error = null
        scope.launch {
            try {
                val challenge = withContext(Dispatchers.IO) { client.requestOtp(channel, destination) }
                challengeId = challenge.challengeId
                resendSeconds = 60
                code = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                error = readableError(exception)
            } finally {
                busy = false
            }
        }
    }
    val yandexLauncher = rememberLauncherForActivityResult(yandexSdk.contract) { result ->
        when (result) {
            is YandexAuthResult.Success -> {
                socialBusy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { client.signInWithYandex(result.token.value) } }
                        .onSuccess { session -> withContext(Dispatchers.IO) { vault.save(session) }; onAuthenticated(session) }
                        .onFailure { error = it.message ?: "Ошибка входа через Яндекс ID" }
                    socialBusy = false
                }
            }
            is YandexAuthResult.Failure -> error = result.exception.message ?: "Не удалось войти через Яндекс ID"
            YandexAuthResult.Cancelled -> Unit
        }
    }

    Scaffold { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding()
        ) {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 36.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.material3.Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Default.Videocam,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(18.dp)
                            )
                        }
                        Text("MOMENTO", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                        Text("Твои моменты. Твоя история.", style = MaterialTheme.typography.headlineSmall)
                        Text("Снимай 2–5 секунд настоящей жизни и собирай её в мини-влоги вместе с близкими.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MomentoPrimaryAction("Попробовать без аккаунта", onClick = onLocalPreview)
                        Text("Камера, личный архив и монтаж доступны без интернета. Записи хранятся отдельно от любого аккаунта.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    OutlinedButton(
                        onClick = onGuest,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) { Text("Смотреть публичные комнаты", style = MaterialTheme.typography.titleMedium) }
                }
                if (!apiConfigured) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Вход пока недоступен", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text("Сервер MOMENTO в этой сборке не настроен. Чтобы проверить камеру и монтаж, выбери «Попробовать без аккаунта».", color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(if (challengeId == null) "Войти или создать аккаунт" else "Подтверждение входа", style = MaterialTheme.typography.titleLarge)
                            if (challengeId == null) {
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    SegmentedButton(selected = contactType == "EMAIL", onClick = { if (contactType != "EMAIL") { contactType = "EMAIL"; contact = ""; error = null } }, enabled = canEdit, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) { Text("Почта") }
                                    SegmentedButton(selected = contactType == "PHONE", onClick = { if (contactType != "PHONE") { contactType = "PHONE"; contact = ""; error = null } }, enabled = canEdit, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) { Text("Телефон") }
                                }
                                OutlinedTextField(
                                    value = contact,
                                    onValueChange = { contact = it.take(254); error = null },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = apiConfigured && canEdit,
                                    singleLine = true,
                                    isError = contact.isNotBlank() && !validContact,
                                    supportingText = {
                                        if (contact.isNotBlank() && !validContact) {
                                            Text(if (contactType == "EMAIL") "Укажи адрес в формате name@example.com" else "Введи номер с кодом страны, например +7 999 123-45-67")
                                        }
                                    },
                                    label = { Text(if (contactType == "EMAIL") "Электронная почта" else "Номер телефона") },
                                    placeholder = { Text(if (contactType == "EMAIL") "name@example.com" else "+79991234567") },
                                    keyboardOptions = KeyboardOptions(keyboardType = if (contactType == "EMAIL") KeyboardType.Email else KeyboardType.Phone),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                Button(
                                    onClick = ::requestSignInCode,
                                    enabled = apiConfigured && canEdit && validContact,
                                    modifier = Modifier.fillMaxWidth().height(54.dp),
                                    shape = RoundedCornerShape(18.dp)
                                ) { if (busy) CircularProgressIndicator() else Text("Получить код") }
                            } else {
                                Text("Код отправлен на $contact", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedTextField(value = code, onValueChange = { code = it.filter { digit -> digit in '0'..'9' }.take(6); error = null }, modifier = Modifier.fillMaxWidth(), enabled = canEdit, singleLine = true, label = { Text("Код подтверждения") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                                Button(
                                    onClick = {
                                        val activeChallenge = challengeId ?: return@Button
                                        val submittedCode = code
                                        busy = true; error = null
                                        scope.launch {
                                            try {
                                                val session = withContext(Dispatchers.IO) {
                                                    client.verifyOtp(activeChallenge, submittedCode).also { vault.save(it) }
                                                }
                                                onAuthenticated(session)
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (exception: Exception) {
                                                error = readableError(exception)
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                    enabled = apiConfigured && canEdit && code.length == 6,
                                    modifier = Modifier.fillMaxWidth().height(54.dp)
                                ) { Text("Подтвердить и войти") }
                                androidx.compose.material3.TextButton(
                                    onClick = ::requestSignInCode,
                                    enabled = apiConfigured && canEdit && resendSeconds == 0
                                ) { Text(if (resendSeconds > 0) "Повторить через ${resendSeconds} с" else "Отправить код ещё раз") }
                                androidx.compose.material3.TextButton(onClick = { challengeId = null; code = ""; error = null; resendSeconds = 0 }, enabled = canEdit) { Text("Изменить контакт") }
                            }
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Другие способы входа", style = MaterialTheme.typography.titleMedium)
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                if (activity == null || BuildConfig.GOOGLE_SERVER_CLIENT_ID.isBlank()) {
                                    error = "Google Sign-In не настроен"
                                } else {
                                    socialBusy = true; error = null
                                    scope.launch {
                                        runCatching {
                                            val nonce = withContext(Dispatchers.IO) { client.requestGoogleNonce() }
                                            val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_SERVER_CLIENT_ID).setNonce(nonce).build()
                                            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                                            val response = credentialManager.getCredential(context = activity, request = request)
                                            val credential = response.credential as? CustomCredential ?: throw IllegalStateException("Неподдерживаемые учётные данные")
                                            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) throw IllegalStateException("Неподдерживаемые учётные данные")
                                            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                                            val session = withContext(Dispatchers.IO) { client.signInWithGoogle(token, nonce) }
                                            withContext(Dispatchers.IO) { vault.save(session) }
                                            onAuthenticated(session)
                                        }.onFailure { error = it.message ?: "Ошибка Google" }
                                        socialBusy = false
                                    }
                                }
                            },
                            enabled = apiConfigured && !busy && !socialBusy && BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)
                        ) { Text("Продолжить с Google") }
                        androidx.compose.material3.OutlinedButton(onClick = { yandexLauncher.launch(YandexAuthLoginOptions()) }, enabled = apiConfigured && !busy && !socialBusy && BuildConfig.YANDEX_CLIENT_ID.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("Продолжить с Яндекс ID") }
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                if (activity == null || !BuildConfig.VK_CLIENT_CONFIGURED) {
                                    error = "VK ID не настроен"
                                } else {
                                    socialBusy = true; error = null
                                    scope.launch {
                                        VKID.instance.authorize(activity, object : VKIDAuthCallback {
                                            override fun onAuth(accessToken: com.vk.id.AccessToken) {
                                                scope.launch {
                                                    runCatching {
                                                        val session = withContext(Dispatchers.IO) { client.signInWithVk(accessToken.token) }
                                                        withContext(Dispatchers.IO) { vault.save(session) }
                                                        onAuthenticated(session)
                                                    }.onFailure { error = it.message ?: "Ошибка VK ID" }
                                                    socialBusy = false
                                                }
                                            }
                                            override fun onFail(fail: VKIDAuthFail) { error = fail.toString(); socialBusy = false }
                                        })
                                    }
                                }
                            },
                            enabled = apiConfigured && !busy && !socialBusy && BuildConfig.VK_CLIENT_CONFIGURED,
                            modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)
                        ) { Text("Продолжить с VK ID") }
                        if (socialBusy) CircularProgressIndicator()
                    }
                }
                item { Text("Продолжая, ты подтверждаешь, что используешь собственный контакт.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}


@Composable
fun ProfileAuthSection(
    accessToken: String,
    client: MomentoAuthClient,
    onLogout: () -> Unit,
    busy: Boolean
) {
    var contactType by remember { mutableStateOf("EMAIL") }
    var contact by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var challengeId by remember { mutableStateOf<String?>(null) }
    var linkingBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val yandexSdk = remember(context) { YandexAuthSdk.create(YandexAuthOptions(context)) }
    val yandexLinkLauncher = rememberLauncherForActivityResult(yandexSdk.contract) { result ->
        when (result) {
            is YandexAuthResult.Success -> {
                linkingBusy = true
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { client.linkYandex(accessToken, result.token.value) }
                    }.onSuccess { message = "Яндекс ID привязан к аккаунту" }
                        .onFailure { message = it.message ?: "Не удалось привязать Яндекс ID" }
                    linkingBusy = false
                }
            }
            is YandexAuthResult.Failure -> message = result.exception.message ?: "Не удалось авторизоваться в Яндекс ID"
            YandexAuthResult.Cancelled -> Unit
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Аккаунт MOMENTO", style = MaterialTheme.typography.titleLarge)
        Text("Сессия защищена Android Keystore. Подключай несколько способов входа к одному аккаунту.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Привязать социальный аккаунт", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = {
                if (activity == null) {
                    message = "Не удалось открыть Google Sign-In"
                } else {
                    linkingBusy = true; message = null
                    scope.launch {
                        runCatching {
                            val nonce = withContext(Dispatchers.IO) { client.requestGoogleNonce() }
                            val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_SERVER_CLIENT_ID)
                                .setNonce(nonce)
                                .build()
                            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                            val response = credentialManager.getCredential(context = activity, request = request)
                            val credential = response.credential as? CustomCredential
                                ?: throw IllegalStateException("Google вернул неподдерживаемые учётные данные")
                            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                throw IllegalStateException("Google вернул неподдерживаемые учётные данные")
                            }
                            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                            withContext(Dispatchers.IO) { client.linkGoogle(accessToken, idToken, nonce) }
                        }.onSuccess { message = "Google привязан к аккаунту" }
                            .onFailure { message = it.message ?: "Не удалось привязать Google" }
                        linkingBusy = false
                    }
                }
            },
            enabled = BuildConfig.MOMENTO_API_CONFIGURED && BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank() && !linkingBusy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Привязать Google") }
        Button(
            onClick = { yandexLinkLauncher.launch(YandexAuthLoginOptions()) },
            enabled = BuildConfig.MOMENTO_API_CONFIGURED && BuildConfig.YANDEX_CLIENT_ID.isNotBlank() && !linkingBusy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Привязать Яндекс ID") }
        Button(
            onClick = {
                if (activity == null || !BuildConfig.VK_CLIENT_CONFIGURED) {
                    message = "VK ID не настроен для этой сборки"
                } else {
                    linkingBusy = true; message = null
                    scope.launch {
                        VKID.instance.authorize(activity, object : VKIDAuthCallback {
                            override fun onAuth(providerToken: com.vk.id.AccessToken) {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) { client.linkVk(accessToken, providerToken.token) }
                                    }.onSuccess { message = "VK ID привязан к аккаунту" }
                                        .onFailure { message = it.message ?: "Не удалось привязать VK ID" }
                                    linkingBusy = false
                                }
                            }
                            override fun onFail(fail: VKIDAuthFail) {
                                message = fail.toString()
                                linkingBusy = false
                            }
                        })
                    }
                }
            },
            enabled = BuildConfig.MOMENTO_API_CONFIGURED && BuildConfig.VK_CLIENT_CONFIGURED && !linkingBusy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Привязать VK ID") }
        Text("Привязка email или телефона", style = MaterialTheme.typography.titleMedium)
        if (challengeId == null) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = contactType == "EMAIL",
                    onClick = { contactType = "EMAIL"; contact = "" },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("Email") }
                SegmentedButton(
                    selected = contactType == "PHONE",
                    onClick = { contactType = "PHONE"; contact = "" },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("Телефон") }
            }
            OutlinedTextField(
                value = contact,
                onValueChange = { contact = it.take(254); message = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (contactType == "EMAIL") "Новый email" else "Новый телефон +79991234567") },
                keyboardOptions = KeyboardOptions(keyboardType = if (contactType == "EMAIL") KeyboardType.Email else KeyboardType.Phone)
            )
            Button(
                onClick = {
                    linkingBusy = true; message = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { client.requestContactLinkOtp(accessToken, contactType, contact.trim()) }
                        }.onSuccess { challengeId = it.challengeId }
                            .onFailure { message = it.message ?: "Не удалось отправить код" }
                        linkingBusy = false
                    }
                },
                enabled = BuildConfig.MOMENTO_API_CONFIGURED && !linkingBusy && contact.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { if (linkingBusy) CircularProgressIndicator() else Text("Отправить код для привязки") }
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Код из письма или SMS") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
            Button(
                onClick = {
                    linkingBusy = true; message = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { client.verifyContactLinkOtp(accessToken, challengeId!!, code) }
                        }.onSuccess {
                            message = "Способ входа успешно привязан"
                            challengeId = null; contact = ""; code = ""
                        }.onFailure { message = it.message ?: "Не удалось привязать контакт" }
                        linkingBusy = false
                    }
                },
                enabled = !linkingBusy && code.length == 6,
                modifier = Modifier.fillMaxWidth()
            ) { if (linkingBusy) CircularProgressIndicator() else Text("Подтвердить привязку") }
            Button(
                onClick = { challengeId = null; code = ""; message = null },
                enabled = !linkingBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Отмена") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Button(onClick = onLogout, enabled = !busy && !linkingBusy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator() else Text("Выйти из аккаунта")
        }
    }
}
