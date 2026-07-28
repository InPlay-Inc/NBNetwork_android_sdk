package com.nanobeaconnetwork.model

data class ScanState(
    val isScanning: Boolean = false,
    val bleEnabled: Boolean = true,
    val gpsEnabled: Boolean = true,
    val hasPermissions: Boolean = false,
)

data class ScanEvent(
    val eidHex: String,
    val rssi: Int,
    val timestamp: Long,
    val reported: Boolean,
)

data class ScanLogEntry(
    val time: String,
    val eidPrefix: String,
    val rssi: Int,
    val status: String,  // "Queued" | "Reported" | "Duplicate" | "Failed"
)
