package com.nanobeaconnetwork.model

data class ReportStats(
    val todayScanCount: Int = 0,
    val todayReportCount: Int = 0,   // records the server actually acknowledged (stored + deduped)
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    // Records the server silently dropped despite a 2xx (unknown/forged EID). A non-zero
    // value means uploads are reaching the server but the EIDs aren't resolvable there.
    val droppedCount: Int = 0,
    val successRate: Float = 0f,
    val rateLimited: Boolean = false,
)
