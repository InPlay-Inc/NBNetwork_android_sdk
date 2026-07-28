package com.nanobeaconnetwork.ble

import com.nanobeaconnetwork.internal.config.ServerConfigManager
import com.nanobeaconnetwork.internal.prefs.SdkPrefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DeduplicatorTest {
    private lateinit var dedup: Deduplicator

    // Dedup keys are BLE MAC addresses (EID hex is used as a fallback key elsewhere).
    private val addr1 = "AA:BB:CC:DD:EE:01"
    private val addr2 = "AA:BB:CC:DD:EE:02"

    @Before fun setup() {
        val mockPrefs = mock(SdkPrefs::class.java)
        `when`(mockPrefs.dedupWindowSeconds).thenReturn(300)
        val config = ServerConfigManager(mockPrefs)
        dedup = Deduplicator(config)
    }

    @Test fun `first occurrence is not duplicate`() = assertFalse(dedup.isDuplicate(addr1))

    @Test fun `same address within window is duplicate`() {
        dedup.isDuplicate(addr1)
        assertTrue(dedup.isDuplicate(addr1))
    }

    @Test fun `different addresses do not interfere`() {
        dedup.isDuplicate(addr1)
        assertFalse(dedup.isDuplicate(addr2))
    }

    @Test fun `clear resets state`() {
        dedup.isDuplicate(addr1)
        dedup.clear()
        assertFalse(dedup.isDuplicate(addr1))
    }

    @Test fun `address is not duplicate after dedup window expires`() {
        val mockPrefs = mock(SdkPrefs::class.java)
        `when`(mockPrefs.dedupWindowSeconds).thenReturn(1) // 1-second window
        val shortConfig = ServerConfigManager(mockPrefs)
        val shortDedup = Deduplicator(shortConfig)

        assertFalse(shortDedup.isDuplicate(addr1))
        assertTrue(shortDedup.isDuplicate(addr1))   // still in window
        Thread.sleep(1200)                           // wait for window to expire
        assertFalse(shortDedup.isDuplicate(addr1))  // expired → not duplicate
    }

    @Test fun `more than 1000 entries triggers cache cleanup without crash`() {
        (0 until 1002).forEach { i -> assertFalse(dedup.isDuplicate("addr-$i")) }
        // entry 1001 triggers cleanup; should still work correctly
        assertFalse(dedup.isDuplicate("addr-final"))
    }
}
