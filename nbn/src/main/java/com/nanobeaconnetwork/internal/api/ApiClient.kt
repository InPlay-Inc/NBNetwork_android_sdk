package com.nanobeaconnetwork.internal.api

import com.nanobeaconnetwork.internal.prefs.NbnPrefs
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

internal class ApiClient(
    private val prefs: NbnPrefs,
    private val ensureAnonymousTokenFn: suspend () -> String?,
    private val debug: Boolean = false,
) {
    private var _service: ApiService? = null
    private var _baseUrl: String = ""

    val service: ApiService
        get() = synchronized(this) {
            val raw = prefs.serverUrl.trimEnd('/')
            require(raw.isNotEmpty()) { "Server URL is not configured" }
            val url = "$raw/"
            if (_service == null || url != _baseUrl) {
                _service = buildService(url)
                _baseUrl = url
            }
            _service!!
        }

    private fun buildService(baseUrl: String): ApiService {
        val authInterceptor = AuthInterceptor(prefs, ensureAnonymousTokenFn)

        val clientBuilder = OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)

        if (debug) {
            clientBuilder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    redactHeader("Authorization")
                    level = HttpLoggingInterceptor.Level.BODY
                }
            )
        }

        val gson = GsonBuilder().serializeNulls().create()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiService::class.java)
    }

    fun invalidate() = synchronized(this) {
        _service = null
        _baseUrl = ""
    }
}
