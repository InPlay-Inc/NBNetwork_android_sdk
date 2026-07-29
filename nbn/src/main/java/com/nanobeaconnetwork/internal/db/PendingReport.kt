package com.nanobeaconnetwork.internal.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_reports",
    indices = [Index(value = ["eidHex"], unique = true)]
)
internal data class PendingReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eidHex: String,
    val payloadHex: String,
    val rssi: Int,
    val latitude: Double,
    val longitude: Double,
    val timestamp: String,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
