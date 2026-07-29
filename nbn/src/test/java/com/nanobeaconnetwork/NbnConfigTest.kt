package com.nanobeaconnetwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NbnConfigTest {

    @Test fun `builder uses release-safe defaults`() {
        val config = NbnConfig.Builder().build()

        // null = the production endpoint; the reporting server is not a host config knob.
        assertNull(config.debugServerUrl)
        assertEquals(NbnConfig.ScanSource.LIBRARY_SCAN, config.scanSource)
        assertEquals(NbnConfig.ScanMode.LOW_POWER, config.scanMode)
        assertEquals(NbnConfig.LogLevel.WARN, config.logLevel)
        // On by default, but gated at runtime on ACCESS_BACKGROUND_LOCATION (see BootReceiver).
        assertTrue(config.restartOnBoot)
    }

    @Test fun `reboot recovery can be opted out`() {
        val config = NbnConfig.Builder()
            .restartOnBoot(false)
            .build()

        assertFalse(config.restartOnBoot)
    }

    @Test fun `debug server URL is normalized and may contain a base path`() {
        val config = NbnConfig.Builder()
            .debugServerUrl("  https://example.com/tenant/  ")
            .build()

        assertEquals("https://example.com/tenant", config.debugServerUrl)
    }

    @Test fun `invalid debug server URLs fail at configuration time`() {
        listOf(
            "",
            "example.com",
            "ftp://example.com",
            "https://",
            "https://example.com/path?query=1",
            "https://example.com/path#fragment",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                NbnConfig.Builder().debugServerUrl(value).build()
            }
        }
    }

    @Test fun `HTTP remains available for local development`() {
        val config = NbnConfig.Builder().debugServerUrl("http://127.0.0.1:18080").build()
        assertEquals("http://127.0.0.1:18080", config.debugServerUrl)
    }
}
