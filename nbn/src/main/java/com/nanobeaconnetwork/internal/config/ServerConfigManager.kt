package com.nanobeaconnetwork.internal.config

import com.nanobeaconnetwork.internal.prefs.NbnPrefs

internal class ServerConfigManager(private val prefs: NbnPrefs) {
    var dedupWindowMs: Long
        get() = prefs.dedupWindowSeconds.toLong() * 1000
        set(v) {
            prefs.dedupWindowSeconds = (v / 1000)
                .coerceIn(MIN_DEDUP_WINDOW_SECONDS.toLong(), MAX_DEDUP_WINDOW_SECONDS.toLong())
                .toInt()
        }

    var reportMinIntervalMs: Long
        get() = prefs.reportMinIntervalSeconds.toLong() * 1000
        set(v) {
            prefs.reportMinIntervalSeconds = (v / 1000)
                .coerceIn(MIN_REPORT_INTERVAL_SECONDS.toLong(), MAX_REPORT_INTERVAL_SECONDS.toLong())
                .toInt()
        }

    var batchThreshold: Int
        get() = prefs.reportBatchThreshold
        set(v) {
            prefs.reportBatchThreshold = v.coerceIn(MIN_BATCH_THRESHOLD, MAX_BATCH_THRESHOLD)
        }

    fun applyServerConfig(config: Map<String, Any?>) {
        // Server sends values as JSON strings ("300"); Gson may also parse numeric
        // JSON as Double. Accept both so config actually applies.
        config.intOrNull("dedup_window_seconds")
            ?.takeIf { it in MIN_DEDUP_WINDOW_SECONDS..MAX_DEDUP_WINDOW_SECONDS }
            ?.let { prefs.dedupWindowSeconds = it }
        config.intOrNull("report_min_interval_seconds")
            ?.takeIf { it in MIN_REPORT_INTERVAL_SECONDS..MAX_REPORT_INTERVAL_SECONDS }
            ?.let { prefs.reportMinIntervalSeconds = it }
        config.intOrNull("report_batch_threshold")
            ?.takeIf { it in MIN_BATCH_THRESHOLD..MAX_BATCH_THRESHOLD }
            ?.let { prefs.reportBatchThreshold = it }
    }

    private fun Map<String, Any?>.intOrNull(key: String): Int? = when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    private companion object {
        const val MIN_DEDUP_WINDOW_SECONDS = 30
        const val MAX_DEDUP_WINDOW_SECONDS = 3_600
        const val MIN_REPORT_INTERVAL_SECONDS = 1
        const val MAX_REPORT_INTERVAL_SECONDS = 300
        const val MIN_BATCH_THRESHOLD = 10
        const val MAX_BATCH_THRESHOLD = 500
    }
}
