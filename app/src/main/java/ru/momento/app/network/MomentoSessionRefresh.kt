package ru.momento.app.network

import ru.momento.app.BuildConfig

/** Shared refresh path for foreground and WorkManager.
 * Serializes refresh-token rotation in this process and validates account ownership.
 * Caller must run on Dispatchers.IO.
 */
object MomentoSessionRefresh {
    private val lock = Any()

    fun refreshForOwner(vault: MomentoSessionVault, owner: String): MomentoAuthSession? =
        synchronized(lock) {
            if (vault.readVerifiedAccountId() != owner) return@synchronized null
            val current = vault.read() ?: return@synchronized null
            if (current.expiresAtMillis > System.currentTimeMillis() + 30_000L) return@synchronized current
            val client = MomentoAuthClient(BuildConfig.MOMENTO_API_BASE_URL)
            val refreshed = client.refresh(current.refreshToken)
            val actualOwner = client.authenticatedUserId(refreshed.accessToken)
            if (actualOwner != owner) return@synchronized null
            if (vault.readVerifiedAccountId() != owner ||
                vault.read()?.refreshToken != current.refreshToken) return@synchronized null
            vault.save(refreshed, owner)
            refreshed
        }
}
