package ru.momento.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.momento.app.BuildConfig
import java.io.IOException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.net.HttpURLConnection
import java.net.URL

/** Server room DTO. Local-only rooms remain in LocalSpaceDatabase until sync is explicitly implemented. */
data class RemoteSpace(
    val id: String,
    val name: String,
    val description: String,
    val kind: String,
    val visibility: String,
    val joinPolicy: String,
    val memberCount: Int,
    val createdAt: String
)

data class CreatedInvite(val inviteId: String, val inviteUrl: String, val expiresAt: String)
data class JoinRequest(
    val id: String,
    val userId: String,
    val displayName: String,
    val status: String,
    val createdAt: String
)

data class RemoteCapsule(
    val id: String,
    val releaseAt: String,
    val createdAt: String,
    val unlocked: Boolean,
    val momentId: String?
)

data class RemoteMoment(
    val id: String,
    val ownerId: String,
    val capturedAt: String,
    val durationMs: Int,
    val caption: String?,
    val authorDisplayName: String
)

data class TimelineCursor(
    val before: String,
    val beforeId: String
)

data class RemoteTimelinePage(
    val items: List<RemoteMoment>,
    val nextCursor: TimelineCursor?
)

class MomentoApiException(
    val statusCode: Int,
    val errorCode: String,
    message: String
) : IOException(message)

/**
 * Minimal authenticated API client for room operations.
 * The caller must provide a valid OIDC access token; this class never stores tokens.
 * The placeholder API URL intentionally fails until a real HTTPS endpoint is configured.
 */
class MomentoApiClient(
    private val baseUrl: String = BuildConfig.MOMENTO_API_BASE_URL
) {
    private companion object {
        const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024
        val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    }
    /** All subscription data comes from the server; never fabricate a local subscription. */
    suspend fun getSubscriptions(accessToken: String): JSONObject = withContext(Dispatchers.IO) {
        request("GET", "/v1/me/subscriptions", accessToken)
    }

    suspend fun getSubscriptionUpdates(accessToken: String): JSONObject = withContext(Dispatchers.IO) {
        request("GET", "/v1/me/subscription-updates", accessToken)
    }

    suspend fun setRoomFollow(accessToken: String, roomId: String, follow: Boolean): JSONObject = withContext(Dispatchers.IO) {
        request(if (follow) "PUT" else "DELETE", "/v1/spaces/${roomId.asUuidPathSegment()}/subscription", accessToken)
    }

    suspend fun setAuthorFollow(accessToken: String, authorId: String, follow: Boolean): JSONObject = withContext(Dispatchers.IO) {
        request(if (follow) "PUT" else "DELETE", "/v1/authors/${authorId.asUuidPathSegment()}/subscription", accessToken)
    }

    suspend fun publishRoom(accessToken: String, roomId: String): JSONObject = withContext(Dispatchers.IO) {
        request("POST", "/v1/spaces/${roomId.asUuidPathSegment()}/publish", accessToken, JSONObject())
    }

    suspend fun attachUploadedMoment(accessToken: String, roomId: String, momentId: String): JSONObject = withContext(Dispatchers.IO) {
        request("POST", "/v1/spaces/${roomId.asUuidPathSegment()}/moments", accessToken,
            JSONObject().put("momentId", momentId.asUuidPathSegment()))
    }

    /** Authenticated private video retrieval. Cache files remain inside app-private storage. */
    suspend fun downloadMomentVideo(accessToken: String, momentId: String, target: File): File = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        require(base.startsWith("https://")) { "Для видео требуется HTTPS" }
        val connection = (URL("$base/v1/moments/${momentId.asUuidPathSegment()}/video")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            if (accessToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            if (connection.responseCode != 200) {
                val status = connection.responseCode
                throw MomentoApiException(status, "VIDEO_UNAVAILABLE", when (status) {
                    401 -> "Сессия истекла. Войди снова"
                    403, 404 -> "Нет доступа к этому моменту"
                    else -> "Видео недоступно: HTTP $status"
                })
            }
            val size = connection.contentLengthLong
            if (size < 1 || size > MAX_UPLOAD_BYTES) throw IOException("Недопустимый размер видео")
            val temporary = File(target.parentFile, target.name + ".part")
            try {
                connection.inputStream.use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
                if (temporary.length() != size) throw IOException("Видео получено не полностью")
                if (!temporary.renameTo(target)) throw IOException("Не удалось сохранить видео")
                target
            } finally { temporary.delete() }
        } finally { connection.disconnect() }
    }

    suspend fun listSpaces(accessToken: String): List<RemoteSpace> = withContext(Dispatchers.IO) {
        val response = request("GET", "/v1/spaces", accessToken)
        val items = response.optJSONArray("items") ?: JSONArray()
        buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(item.toRemoteSpace())
            }
        }
    }

    suspend fun discoverSpaces(accessToken: String, query: String = ""): List<RemoteSpace> = withContext(Dispatchers.IO) {
        require(query.length <= 120) { "Слишком длинный поисковый запрос" }
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        val response = request("GET", "/v1/discovery/spaces?q=$encoded", accessToken)
        val items = response.optJSONArray("items") ?: JSONArray()
        buildList(items.length()) {
            for (index in 0 until items.length()) add(items.getJSONObject(index).toRemoteSpace())
        }
    }

    suspend fun joinPublicSpace(accessToken: String, spaceId: String): JSONObject = withContext(Dispatchers.IO) {
        request("POST", "/v1/spaces/${spaceId.asUuidPathSegment()}/join", accessToken, JSONObject())
    }

    suspend fun listJoinRequests(accessToken: String, spaceId: String): List<JoinRequest> = withContext(Dispatchers.IO) {
        val response = request("GET", "/v1/spaces/${spaceId.asUuidPathSegment()}/join-requests", accessToken)
        val items = response.optJSONArray("items") ?: JSONArray()
        buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(JoinRequest(item.getString("id"), item.getString("user_id"), item.optString("display_name"), item.getString("status"), item.getString("created_at")))
            }
        }
    }

    suspend fun reviewJoinRequest(accessToken: String, spaceId: String, requestId: String, approve: Boolean): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("decision", if (approve) "APPROVED" else "REJECTED")
        request("POST", "/v1/spaces/${spaceId.asUuidPathSegment()}/join-requests/${requestId.asUuidPathSegment()}/review", accessToken, body)
    }

    suspend fun createSyncedRoom(accessToken: String, localId: String, name: String, description: String, visibility: String): String = withContext(Dispatchers.IO) {
        require(visibility == "PUBLIC" || visibility == "PRIVATE")
        val body = JSONObject().put("clientRoomId", localId.asUuidPathSegment())
            .put("name",name).put("description",description).put("kind","CIRCLE")
            .put("visibility",visibility)
            .put("joinPolicy",if (visibility == "PUBLIC") "OPEN" else "INVITE_ONLY")
        request("POST","/v1/spaces",accessToken,body).getString("id")
    }

    suspend fun createPrivateCircle(accessToken: String, name: String): RemoteSpace = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        require(cleanName.length in 2..60) { "Название комнаты должно содержать от 2 до 60 символов" }
        val body = JSONObject()
            .put("name", cleanName)
            .put("description", "")
            .put("kind", "CIRCLE")
            .put("visibility", "PRIVATE")
            .put("joinPolicy", "INVITE_ONLY")
        request("POST", "/v1/spaces", accessToken, body).toRemoteSpace()
    }

    suspend fun createInvite(
        accessToken: String,
        spaceId: String,
        expiresInHours: Int = 168,
        maxUses: Int? = null
    ): CreatedInvite = withContext(Dispatchers.IO) {
        require(expiresInHours in 1..720)
        require(maxUses == null || maxUses in 1..500)
        val body = JSONObject().put("expiresInHours", expiresInHours)
        if (maxUses != null) body.put("maxUses", maxUses)
        val response = request("POST", "/v1/spaces/${spaceId.asPathSegment()}/invites", accessToken, body)
        CreatedInvite(response.getString("inviteId"), response.getString("inviteUrl"), response.getString("expiresAt"))
    }

    suspend fun revokeInvite(accessToken: String, spaceId: String, inviteId: String) = withContext(Dispatchers.IO) {
        request("DELETE", "/v1/spaces/${spaceId.asUuidPathSegment()}/invites/${inviteId.asUuidPathSegment()}", accessToken)
    }

    suspend fun acceptInvite(accessToken: String, token: String): String = withContext(Dispatchers.IO) {
        require(token.matches(Regex("[A-Za-z0-9_-]{40,64}"))) { "Некорректное приглашение" }
        request("POST", "/v1/invites/${token.asPathSegment()}/accept", accessToken, JSONObject())
            .getString("spaceId")
    }

    /** Loads one page of the shared room timeline. The server enforces membership and visibility. */
    suspend fun getSpaceTimeline(
        accessToken: String,
        spaceId: String,
        cursor: TimelineCursor? = null,
        limit: Int = 20
    ): RemoteTimelinePage = withContext(Dispatchers.IO) {
        require(limit in 1..50) { "Размер страницы должен быть от 1 до 50" }
        val id = spaceId.asUuidPathSegment()
        val query = buildString {
            append("?limit=").append(limit)
            if (cursor != null) {
                require(cursor.before.isNotBlank()) { "Не задано время курсора" }
                append("&before=").append(java.net.URLEncoder.encode(cursor.before, Charsets.UTF_8.name()))
                append("&beforeId=").append(cursor.beforeId.asUuidPathSegment())
            }
        }
        val response = request("GET", "/v1/spaces/$id/moments$query", accessToken)
        val array = response.optJSONArray("items") ?: JSONArray()
        val items = buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    RemoteMoment(
                        id = item.getString("id"),
                        ownerId = item.getString("owner_id"),
                        capturedAt = item.getString("captured_at"),
                        durationMs = item.getInt("duration_ms"),
                        caption = if (item.isNull("caption")) null else item.optString("caption"),
                        authorDisplayName = item.optString("display_name", "MOMENTO user")
                    )
                )
            }
        }
        val rawCursor = response.optJSONObject("nextCursor")
        val nextCursor = if (rawCursor == null) null else {
            TimelineCursor(
                before = rawCursor.getString("before"),
                beforeId = rawCursor.getString("beforeId")
            )
        }
        RemoteTimelinePage(items, nextCursor)
    }

    /** Server-side capsules only; local moments have no server UUID until uploaded. */
    suspend fun listCapsules(accessToken: String): List<RemoteCapsule> = withContext(Dispatchers.IO) {
        val items = request("GET", "/v1/me/capsules", accessToken).optJSONArray("items") ?: JSONArray()
        buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(RemoteCapsule(
                    id = item.getString("id"),
                    releaseAt = item.getString("release_at"),
                    createdAt = item.getString("created_at"),
                    unlocked = item.getBoolean("unlocked"),
                    momentId = if (item.isNull("moment_id")) null else item.optString("moment_id")
                ))
            }
        }
    }

    suspend fun createCapsule(accessToken: String, remoteMomentId: String, releaseAtIso: String): JSONObject = withContext(Dispatchers.IO) {
        require(releaseAtIso.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z"))) { "Некорректная дата открытия" }
        request("POST", "/v1/me/capsules", accessToken,
            JSONObject().put("momentId", remoteMomentId.asUuidPathSegment()).put("releaseAt", releaseAtIso))
    }

    suspend fun cancelCapsule(accessToken: String, capsuleId: String): Unit = withContext(Dispatchers.IO) {
        request("DELETE", "/v1/me/capsules/${capsuleId.asUuidPathSegment()}", accessToken)
    }

    /** Upload a locally recorded MP4; only successful server acknowledgement returns an ID.
     * Do not delete local files on failure. Automatic retry requires idempotency support.
     */
    suspend fun uploadMoment(accessToken: String, file: File, capturedAtMillis: Long, clientRequestId: String): String = withContext(Dispatchers.IO) {
        require(accessToken.isNotBlank()) { "Требуется авторизация" }
        require(file.isFile && file.length() in 1L..MAX_UPLOAD_BYTES) { "Недопустимый размер видео" }
        require(capturedAtMillis > 0 && capturedAtMillis <= System.currentTimeMillis() + 300_000) { "Некорректная дата записи" }
        require(clientRequestId.matches(UUID_REGEX)) { "Некорректный идентификатор загрузки" }
        val normalizedBase = baseUrl.trimEnd('/')
        require(normalizedBase.startsWith("https://")) { "Для API разрешён только HTTPS" }
        val connection = (URL(normalizedBase + "/v1/me/moments/upload").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 45_000
            useCaches = false
            doOutput = true
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("Content-Type", "video/mp4")
            setRequestProperty("Accept", "application/json")
            if (accessToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("X-Momento-Captured-At", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(capturedAtMillis)))
            setRequestProperty("X-Momento-Request-Id", clientRequestId)
        }
        try {
            file.inputStream().use { input -> connection.outputStream.use { output -> input.copyTo(output) } }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
            if (status !in 200..299) {
                val code = json.optString("error", "HTTP_ERROR")
                throw MomentoApiException(status, code, code)
            }
            val momentId = json.getJSONObject("moment").getString("id")
            require(momentId.matches(UUID_REGEX)) { "Некорректный ответ сервера" }
            momentId
        } finally {
            connection.disconnect()
        }
    }

    private fun request(
        method: String,
        path: String,
        accessToken: String,
        body: JSONObject? = null
    ): JSONObject {
        val publicRead = method == "GET" && (path.startsWith("/v1/discovery/spaces?") ||
            Regex("^/v1/spaces/[0-9a-f-]{36}/moments\\?").containsMatchIn(path))
        require(accessToken.isNotBlank() || publicRead) { "Требуется авторизация" }
        val normalizedBase = baseUrl.trimEnd('/')
        require(normalizedBase.startsWith("https://")) { "Для API разрешён только HTTPS" }
        val connection = (URL(normalizedBase + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (accessToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
            if (status !in 200..299) {
                val code = json.optString("error", "HTTP_ERROR")
                val message = json.optString("message", code)
                throw MomentoApiException(status, code, message)
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toRemoteSpace() = RemoteSpace(
        id = getString("id"),
        name = getString("name"),
        description = optString("description", ""),
        kind = getString("kind"),
        visibility = getString("visibility"),
        joinPolicy = if (has("join_policy")) getString("join_policy") else getString("joinPolicy"),
        memberCount = optInt("memberCount", optInt("member_count", 1)),
        createdAt = optString("created_at", optString("createdAt"))
    )

    private fun String.asPathSegment(): String {
        require(matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Некорректный идентификатор" }
        return this
    }

    private fun String.asUuidPathSegment(): String {
        require(matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"))) {
            "Некорректный идентификатор комнаты или приглашения"
        }
        return this
    }
}
