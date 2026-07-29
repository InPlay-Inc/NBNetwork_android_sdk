package com.nanobeaconnetwork.internal.config

import com.nanobeaconnetwork.internal.prefs.NbnPrefs
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

class ServerConfigManagerTest {

    @Test fun `applies string and numeric values from server`() {
        val prefs = mock<NbnPrefs>()
        val manager = ServerConfigManager(prefs)

        manager.applyServerConfig(
            mapOf(
                "dedup_window_seconds" to "600",
                "report_min_interval_seconds" to 30.0,
                "report_batch_threshold" to 200,
            )
        )

        verify(prefs).dedupWindowSeconds = 600
        verify(prefs).reportMinIntervalSeconds = 30
        verify(prefs).reportBatchThreshold = 200
    }

    @Test fun `ignores malformed and out of range server values`() {
        val prefs = mock<NbnPrefs>()
        val manager = ServerConfigManager(prefs)

        manager.applyServerConfig(
            mapOf(
                "dedup_window_seconds" to 29,
                "report_min_interval_seconds" to "invalid",
                "report_batch_threshold" to 501,
            )
        )

        verifyNoInteractions(prefs)
    }

    @Test fun `local setters clamp values to documented limits`() {
        val prefs = mock<NbnPrefs>()
        val manager = ServerConfigManager(prefs)

        manager.dedupWindowMs = 1
        manager.reportMinIntervalMs = Long.MAX_VALUE
        manager.batchThreshold = 1

        verify(prefs).dedupWindowSeconds = 30
        verify(prefs).reportMinIntervalSeconds = 300
        verify(prefs).reportBatchThreshold = 10
    }
}
