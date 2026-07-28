package com.nanobeaconnetwork.internal.config

import com.nanobeaconnetwork.internal.prefs.SdkPrefs

internal class ServerConfigManager(private val prefs: SdkPrefs) {
    var dedupWindowMs: Long
        get() = prefs.dedupWindowSeconds.toLong() * 1000
        set(v) { prefs.dedupWindowSeconds = (v / 1000).toInt() }

    var reportMinIntervalMs: Long
        get() = prefs.reportMinIntervalSeconds.toLong() * 1000
        set(v) { prefs.reportMinIntervalSeconds = (v / 1000).toInt() }

    var batchThreshold: Int
        get() = prefs.reportBatchThreshold
        set(v) { prefs.reportBatchThreshold = v }

    fun applyServerConfig(config: Map<String, Any?>) {
        // Server sends values as JSON strings ("300"); Gson may also parse numeric
        // JSON as Double. Accept both so config actually applies.
        config.intOrNull("dedup_window_seconds")?.let { prefs.dedupWindowSeconds = it }
        config.intOrNull("report_min_interval_seconds")?.let { prefs.reportMinIntervalSeconds = it }
        config.intOrNull("report_batch_threshold")?.let { prefs.reportBatchThreshold = it }
    }

    private fun Map<String, Any?>.intOrNull(key: String): Int? = when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
}
