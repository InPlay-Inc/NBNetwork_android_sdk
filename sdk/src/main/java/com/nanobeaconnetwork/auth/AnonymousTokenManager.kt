package com.nanobeaconnetwork.auth

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.nanobeaconnetwork.NbnError
import com.nanobeaconnetwork.internal.api.AnonymousTokenRequest
import com.nanobeaconnetwork.internal.api.ApiClient
import com.nanobeaconnetwork.internal.config.ServerConfigManager
import com.nanobeaconnetwork.internal.prefs.SdkPrefs

/**
 * Manages the long-lived anonymous token used for reporting. The SDK has no user login /
 * account system — reports are sent anonymously, authenticated only by this token (bound to
 * the device's ANDROID_ID for server-side rate limiting).
 */
internal class AnonymousTokenManager(
    private val context: Context,
    private val prefs: SdkPrefs,
    private val apiClient: ApiClient,
) {
    // ServerConfigManager is stateless; a local instance is fine. Applies the `config` field
    // piggybacked on the anonymous-token response.
    private val configManager = ServerConfigManager(prefs)

    /** Returns a cached anonymous token, or fetches one (applying any piggybacked config). */
    @SuppressLint("HardwareIds")
    suspend fun ensureAnonymousToken(): Result<String> = runCatching {
        val existing = prefs.anonymousToken
        if (existing.isNotEmpty()) return@runCatching existing
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val resp = apiClient.service.anonymousToken(AnonymousTokenRequest(deviceId))
        prefs.anonymousToken = resp.token
        resp.config?.let { configManager.applyServerConfig(it) }
        resp.token
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
