package com.nanobeaconnetwork.internal.api

import retrofit2.http.*

internal interface ApiService {
    // Anonymous token (public): authenticates anonymous reporting; response piggybacks `config`.
    @POST("api/v1/auth/anonymous-token")
    suspend fun anonymousToken(@Body body: AnonymousTokenRequest): AnonymousTokenResponse

    // Report
    @POST("api/v1/report/batch")
    suspend fun batchReport(@Body body: BatchReportRequest): BatchReportResponse

    // Config (public): latest client runtime config, fetched on launch.
    @GET("api/v1/config")
    suspend fun getConfig(): ConfigResponse
}
