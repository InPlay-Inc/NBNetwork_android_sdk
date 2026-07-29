package com.nanobeaconnetwork.internal.api

import com.nanobeaconnetwork.internal.prefs.NbnPrefs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the anonymous token to non-auth requests and refetches it on 401.
 * The library has no user login, so this is anonymous-only.
 */
internal class AuthInterceptor(
    private val prefs: NbnPrefs,
    private val ensureAnonymousTokenFn: suspend () -> String?,
) : Interceptor {

    private val anonymousMutex = Mutex()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val isAuthEndpoint = originalRequest.url.encodedPath
            .endsWith("/api/v1/auth/anonymous-token")

        var token = prefs.anonymousToken

        // Lazy anonymous-token fetch: any non-auth call without a token triggers a one-shot
        // fetch so reports work even if the init-time fetch was skipped or failed.
        if (token.isEmpty() && !isAuthEndpoint) {
            token = runBlocking {
                anonymousMutex.withLock {
                    val current = prefs.anonymousToken
                    if (current.isNotEmpty()) current
                    else ensureAnonymousTokenFn().orEmpty()
                }
            }
        }

        val request = if (token.isNotEmpty() && !isAuthEndpoint) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            originalRequest
        }

        val response = chain.proceed(request)

        // Stored anonymous token may have expired — drop it, refetch, and retry once.
        if (!isAuthEndpoint && response.code == 401) {
            val newToken = runBlocking {
                anonymousMutex.withLock {
                    val current = prefs.anonymousToken
                    if (current == token) prefs.anonymousToken = ""
                    ensureAnonymousTokenFn()
                }
            }
            if (!newToken.isNullOrEmpty()) {
                response.close()
                val retried = chain.request().newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .build()
                return chain.proceed(retried)
            }
        }

        return response
    }
}
