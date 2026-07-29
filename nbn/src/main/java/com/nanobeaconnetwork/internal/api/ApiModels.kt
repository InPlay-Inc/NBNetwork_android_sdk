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
    @SerializedName("payload") val payload: String,
    @SerializedName("rssi") val rssi: Int,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("timestamp") val timestamp: String,
)

internal data class BatchReportRequest(
    @SerializedName("reports") val reports: List<ReportItem>,
)

// Server acknowledges a batch with {status, count} (no per-item accept info, no device_id).
// A 2xx response means the whole batch was processed (stored, deduped, or dropped for
// unknown EIDs) and must not be retried.
internal data class BatchReportResponse(
    @SerializedName("status") val status: String? = null,
    @SerializedName("count") val count: Int = 0,
)
