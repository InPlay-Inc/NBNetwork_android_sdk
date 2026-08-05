package com.nanobeaconnetwork.model

data class ReportStats(
    val todayScanCount: Int = 0,
    val todayReportCount: Int = 0, // observations accepted into the durable server chain
    val pendingCount: Int = 0,
    val failedCount: Int = 0, // exhausted six ordinary send opportunities
    val expiredCount: Int = 0,
    val invalidCount: Int = 0,
    val queueFullCount: Int = 0,
    // Sightings deliberately skipped because the same BLE address / EID was already reported
    // within source_min_interval_seconds. Not a failure — these were never meant to be sent.
    val throttledCount: Int = 0,
    val successRate: Float = 0f,
    val rateLimited: Boolean = false,
)
