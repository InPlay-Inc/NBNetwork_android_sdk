package com.nanobeaconnetwork.internal.api

import com.nanobeaconnetwork.internal.prefs.SdkPrefs
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

internal class ApiClient(
    private val prefs: SdkPrefs,
    private val ensureAnonymousTokenFn: suspend () -> String?,
    private val debug: Boolean = false,
) {
    private var _service: ApiService? = null
    private var _baseUrl: String = ""

    val service: ApiService
        get() {
            val raw = prefs.serverUrl.trimEnd('/')
            val url = if (raw.isEmpty()) "http://localhost/" else "$raw/"
            if (_service == null || url != _baseUrl) {
                _service = buildService(url)
                _baseUrl = url
            }
            return _service!!
        }

    private fun buildService(baseUrl: String): ApiService {
        val authInterceptor = AuthInterceptor(prefs, ensureAnonymousTokenFn)

        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)

        if (debug) {
            clientBuilder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
            )
        }

        val gson = GsonBuilder().create()

        return Retrofit.Builder()
            .baseUrl(baseUrl.ifEmpty { "http://localhost/" })
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiService::class.java)
    }

    fun invalidate() {
        _service = null
    }
}
