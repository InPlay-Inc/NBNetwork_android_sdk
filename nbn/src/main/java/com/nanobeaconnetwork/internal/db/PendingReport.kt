package com.nanobeaconnetwork.internal.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_reports",
    indices = [
        Index(value = ["observationId"], unique = true),
        Index(value = ["sourceKey", "slot"], unique = true),
        Index(value = ["slot", "nextAttemptAt"]),
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
    val clientSeenAt: String,
    val failedAttempts: Int = 0,
    val createdAt: Long,
    val expiresAt: Long,
    val nextAttemptAt: Long,
) {
    fun estimatedBytes(): Long =
        observationId.length + sourceKey.length + (batchId?.length ?: 0) + eidHex.length +
            payloadBase64.length + clientSeenAt.length + ESTIMATED_ROW_OVERHEAD

    companion object {
        const val SLOT_PENDING = "pending_latest"
        const val SLOT_IN_FLIGHT = "in_flight"
        private const val ESTIMATED_ROW_OVERHEAD = 256L
    }
}
