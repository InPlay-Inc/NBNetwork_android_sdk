package com.nanobeaconnetwork.internal.api

import com.google.gson.annotations.SerializedName

// Anonymous token
internal data class AnonymousTokenRequest(
    @SerializedName("android_id") val deviceId: String,
)

internal data class AnonymousTokenResponse(
    @SerializedName("access_token") val token: String,
    @SerializedName("config") val config: Map<String, Any?>? = null,
)

internal data class ConfigResponse(
    @SerializedName("config") val config: Map<String, Any?>? = null,
)

// Report
internal data class ReportItem(
    @SerializedName("observation_id") val observationId: String,
    @SerializedName("payload") val payload: String,
    @SerializedName("rssi") val rssi: Int,
    @SerializedName("latitude") val latitude: Double?,
    @SerializedName("longitude") val longitude: Double?,
    @SerializedName("client_seen_at") val clientSeenAt: String,
)

internal data class BatchReportRequest(
    @SerializedName("batch_id") val batchId: String,
    @SerializedName("reports") val reports: List<ReportItem>,
)

// A matching 202 means only that the complete batch entered the durable processing chain.
// No item count, EID existence, device id, or tag-verification result is exposed.
internal data class BatchReportResponse(
    @SerializedName("status") val status: String? = null,
    @SerializedName("batch_id") val batchId: String? = null,
)
