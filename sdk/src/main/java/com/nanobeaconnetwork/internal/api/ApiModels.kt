package com.nanobeaconnetwork.internal.api

import com.google.gson.annotations.SerializedName

// Anonymous token
internal data class AnonymousTokenRequest(@SerializedName("android_id") val deviceId: String)

internal data class AnonymousTokenResponse(
    @SerializedName("access_token") val token: String,
    val config: Map<String, Any?>? = null,
)

internal data class ConfigResponse(val config: Map<String, Any?>? = null)

// Report
internal data class ReportItem(
    val payload: String,
    val rssi: Int,
    val latitude: Double,
    val longitude: Double,
    val timestamp: String,
)

internal data class BatchReportRequest(val reports: List<ReportItem>)

// Server acknowledges a batch with {status, count} (no per-item accept info, no device_id).
// A 2xx response means the whole batch was processed (stored, deduped, or dropped for
// unknown EIDs) and must not be retried.
internal data class BatchReportResponse(val status: String? = null, val count: Int = 0)
