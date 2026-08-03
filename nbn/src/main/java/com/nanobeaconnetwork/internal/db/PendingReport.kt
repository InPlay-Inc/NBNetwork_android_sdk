package com.nanobeaconnetwork.internal.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_reports",
    indices = [
        Index(value = ["observationId"], unique = true),
        Index(value = ["sourceKey", "slot"], unique = true),
        Index(value = ["slot", "nextAttemptElapsedRealtimeMs"]),
        Index(value = ["batchId"]),
    ],
)
internal data class PendingReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val observationId: String,
    val sourceKey: String,
    val slot: String = SLOT_PENDING,
    val batchId: String? = null,
    val eidHex: String,
    val payloadBase64: String,
    val rssi: Int,
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracyMeters: Double?,
    val locationSource: String,
    val locationIsMock: Boolean,
    val clientSeenAt: String,
    val failedAttempts: Int = 0,
    val createdElapsedRealtimeMs: Long,
    val createdWallTimeMs: Long,
    val bootAnchor: String,
    val expiresElapsedRealtimeMs: Long,
    val nextAttemptElapsedRealtimeMs: Long,
) {
    fun estimatedBytes(): Long =
        observationId.length + sourceKey.length + (batchId?.length ?: 0) + eidHex.length +
            payloadBase64.length + locationSource.length + clientSeenAt.length + bootAnchor.length +
            ESTIMATED_ROW_OVERHEAD

    companion object {
        const val SLOT_PENDING = "pending_latest"
        const val SLOT_IN_FLIGHT = "in_flight"
        private const val ESTIMATED_ROW_OVERHEAD = 256L
    }
}
