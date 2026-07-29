package com.nanobeaconnetwork.auth

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.nanobeaconnetwork.NbnError
import com.nanobeaconnetwork.internal.api.AnonymousTokenRequest
import com.nanobeaconnetwork.internal.api.ApiClient
import com.nanobeaconnetwork.internal.config.ServerConfigManager
import com.nanobeaconnetwork.internal.prefs.NbnPrefs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages the long-lived anonymous token used for reporting. The library has no user login /
 * account system — reports are sent anonymously, authenticated only by this token (bound to
 * the device's ANDROID_ID for server-side rate limiting).
 */
internal class AnonymousTokenManager(
    private val context: Context,
    private val prefs: NbnPrefs,
    private val apiClient: ApiClient,
) {
    // ServerConfigManager is stateless; a local instance is fine. Applies the `config` field
    // piggybacked on the anonymous-token response.
    private val configManager = ServerConfigManager(prefs)
    private val tokenMutex = Mutex()

    /** Returns a cached anonymous token, or fetches one (applying any piggybacked config). */
    @SuppressLint("HardwareIds")
    suspend fun ensureAnonymousToken(): Result<String> = runCatching {
        tokenMutex.withLock {
            val existing = prefs.anonymousToken
            if (existing.isNotEmpty()) return@withLock existing
            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: error("ANDROID_ID is unavailable")
            val resp = apiClient.service.anonymousToken(AnonymousTokenRequest(deviceId))
            val token = resp.token.takeIf { it.isNotBlank() }
                ?: error("Anonymous-token response contained an empty access token")
            prefs.anonymousToken = token
            resp.config?.let { configManager.applyServerConfig(it) }
            token
        }
    }.mapError()
}

private fun <T> Result<T>.mapError(): Result<T> = this.recoverCatching { e ->
    val httpCode = (e as? retrofit2.HttpException)?.code()
    val nbnError = when (httpCode) {
        401 -> NbnError(NbnError.CODE_AUTH_FAILED, e.message ?: "Unauthorized")
        429 -> NbnError(NbnError.CODE_RATE_LIMITED, "Rate limited")
        else -> NbnError(NbnError.CODE_NETWORK, e.message ?: "Network error")
    }
    throw NbnException(nbnError)
}

internal class NbnException(val error: NbnError) : Exception(error.message)
