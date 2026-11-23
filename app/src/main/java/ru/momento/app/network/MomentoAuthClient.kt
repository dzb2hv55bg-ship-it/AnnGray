package ru.momento.app.network

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class MomentoAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Int,
    val expiresAtMillis: Long = System.currentTimeMillis() + expiresIn * 1000L
)

data class OtpChallenge(val challengeId: String, val expiresIn: Int)

class MomentoAuthClient(private val baseUrl: String) {
    init {
        require(baseUrl.isEmpty() || baseUrl.startsWith("https://")) { "MOMENTO API must use HTTPS" }
    }

    private fun endpoint(path: String): URL {
        check(baseUrl.isNotEmpty()) { "MOMENTO API is not configured" }
        return URL(baseUrl.trimEnd('/') + path)
    }

    /** Server-verified identity: never derive local archive ownership from an unverified JWT payload. */
    fun authenticatedUserId(accessToken: String): String {
        val connection = (endpoint("/v1/auth/me").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw MomentoApiException(status, "IDENTITY_FAILED", "Cannot verify account")
            val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val userId = JSONObject(payload).getJSONObject("user").getString("id")
            require(java.util.UUID.fromString(userId).toString().equals(userId, ignoreCase = true)) { "Invalid account ID" }
            return userId
        } finally {
            connection.disconnect()
        }
    }

    fun requestOtp(contactType: String, value: String): OtpChallenge {
        require(contactType == "EMAIL" || contactType == "PHONE")
        val response = request("POST", "/v1/auth/otp/request", JSONObject()
            .put("contactType", contactType)
            .put("value", value))
        return OtpChallenge(response.getString("challengeId"), response.getInt("expiresIn"))
    }

    fun verifyOtp(challengeId: String, code: String): MomentoAuthSession {
        val response = request("POST", "/v1/auth/otp/verify", JSONObject()
            .put("challengeId", challengeId)
            .put("code", code))
        return response.toSession()
    }

    fun requestGoogleNonce(): String =
        request("POST", "/v1/auth/providers/google/nonce", JSONObject()).getString("nonce")

    fun signInWithGoogle(idToken: String, nonce: String): MomentoAuthSession =
        request("POST", "/v1/auth/providers/google", JSONObject()
            .put("idToken", idToken)
            .put("nonce", nonce)
            .put("deviceLabel", "Android")).toSession()

    fun signInWithYandex(accessToken: String): MomentoAuthSession =
        providerSignIn("yandex", "accessToken", accessToken)

    fun signInWithVk(accessToken: String): MomentoAuthSession =
        providerSignIn("vk", "accessToken", accessToken)

    fun refresh(refreshToken: String): MomentoAuthSession =
        request("POST", "/v1/auth/refresh", JSONObject().put("refreshToken", refreshToken)).toSession()

    fun requestContactLinkOtp(accessToken: String, contactType: String, value: String): OtpChallenge {
        require(contactType == "EMAIL" || contactType == "PHONE")
        val response = request(
            "POST",
            "/v1/auth/link/otp/request",
            JSONObject().put("contactType", contactType).put("value", value),
            accessToken
        )
        return OtpChallenge(response.getString("challengeId"), response.getInt("expiresIn"))
    }

    fun verifyContactLinkOtp(accessToken: String, challengeId: String, code: String) {
        request(
            "POST",
            "/v1/auth/link/otp/verify",
            JSONObject().put("challengeId", challengeId).put("code", code),
            accessToken
        )
    }

    fun linkGoogle(accessToken: String, idToken: String, nonce: String) {
        linkProvider(accessToken, "google", "idToken", idToken, nonce)
    }

    fun linkYandex(accessToken: String, providerToken: String) {
        linkProvider(accessToken, "yandex", "accessToken", providerToken)
    }

    fun linkVk(accessToken: String, providerToken: String) {
        linkProvider(accessToken, "vk", "accessToken", providerToken)
    }

    private fun linkProvider(accessToken: String, provider: String, tokenField: String, token: String, nonce: String? = null) {
        val body = JSONObject().put(tokenField, token)
        if (nonce != null) body.put("nonce", nonce)
        request("POST", "/v1/auth/link/providers/$provider", body, accessToken)
    }

    fun logout(refreshToken: String) {
        request("POST", "/v1/auth/logout", JSONObject().put("refreshToken", refreshToken))
    }

    private fun providerSignIn(provider: String, tokenField: String, token: String): MomentoAuthSession {
        require(token.isNotBlank())
        return request("POST", "/v1/auth/providers/$provider", JSONObject().put(tokenField, token).put("deviceLabel", "Android")).toSession()
    }

    private fun JSONObject.toSession() = MomentoAuthSession(
        accessToken = getString("accessToken"),
        refreshToken = getString("refreshToken"),
        tokenType = optString("tokenType", "Bearer"),
        expiresIn = getInt("expiresIn")
    )

    private fun request(method: String, path: String, body: JSONObject, accessToken: String? = null): JSONObject {
        val connection = (endpoint(path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (status !in 200..299) {
                throw MomentoApiException(status, json.optString("error", "AUTH_ERROR"), json.optString("message", json.optString("error", "AUTH_ERROR")))
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}

/** Stores MOMENTO tokens encrypted with a non-exportable Android Keystore AES key. */
class MomentoSessionVault(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences("momento_secure_session", Context.MODE_PRIVATE)

    fun save(session: MomentoAuthSession, verifiedAccountId: String? = null) {
        require(verifiedAccountId == null || isCanonicalUuid(verifiedAccountId))
        val payload = JSONObject()
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("expiresIn", session.expiresIn)
            .put("expiresAtMillis", session.expiresAtMillis)
            .apply { if (verifiedAccountId != null) put("verifiedAccountId", verifiedAccountId) }
            .toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun read(): MomentoAuthSession? = readPayload()?.let { json ->
        runCatching {
            MomentoAuthSession(
                json.getString("accessToken"), json.getString("refreshToken"), "Bearer",
                json.getInt("expiresIn"),
                json.optLong("expiresAtMillis", System.currentTimeMillis())
            )
        }.getOrNull()
    }

    /** Only a server-confirmed account ID stored with the encrypted session is trusted offline. */
    fun readVerifiedAccountId(): String? = readPayload()?.optString("verifiedAccountId")
        ?.takeIf { isCanonicalUuid(it) }

    /** Bind the account ID only after /v1/auth/me succeeds for the current access token. */
    fun bindVerifiedAccountId(expectedAccessToken: String, accountId: String): Boolean {
        if (!isCanonicalUuid(accountId)) return false
        val session = read() ?: return false
        if (session.accessToken != expectedAccessToken) return false
        save(session, accountId)
        return true
    }

    private fun isCanonicalUuid(value: String): Boolean = runCatching {
        java.util.UUID.fromString(value).toString().equals(value, ignoreCase = true)
    }.getOrDefault(false)

    private fun readPayload(): JSONObject? {
        val ivText = preferences.getString("iv", null) ?: return null
        val dataText = preferences.getString("data", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)))
            val clear = cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP))
            JSONObject(String(clear, Charsets.UTF_8))
        }.getOrNull()
    }

    fun clear() { preferences.edit().clear().apply() }

    private fun getOrCreateKey(): SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object { private const val KEY_ALIAS = "momento.session.aes.v1" }
}
