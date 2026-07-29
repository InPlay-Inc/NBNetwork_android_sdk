package com.nanobeaconnetwork.ble

import com.nanobeaconnetwork.internal.config.ServerConfigManager
import com.nanobeaconnetwork.internal.prefs.NbnPrefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeduplicatorTest {
    private lateinit var dedup: Deduplicator
    private var nowMs = 0L

    // Dedup keys are BLE MAC addresses (EID hex is used as a fallback key elsewhere).
    private val addr1 = "AA:BB:CC:DD:EE:01"
    private val addr2 = "AA:BB:CC:DD:EE:02"

    @Before fun setup() {
        val mockPrefs = mock(NbnPrefs::class.java)
        `when`(mockPrefs.dedupWindowSeconds).thenReturn(300)
        val config = ServerConfigManager(mockPrefs)
        dedup = Deduplicator(config) { nowMs }
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
        val shortDedup = dedup

        assertFalse(shortDedup.isDuplicate(addr1))
        assertTrue(shortDedup.isDuplicate(addr1))   // still in window
        nowMs += 300_000
        assertFalse(shortDedup.isDuplicate(addr1))  // expired → not duplicate
    }


    @Test fun `concurrent sightings admit exactly one report`() {
        val workers = 16
        val barrier = CyclicBarrier(workers)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val results = (0 until workers).map {
                pool.submit<Boolean> {
                    barrier.await(5, TimeUnit.SECONDS)
                    dedup.isDuplicate(addr1)
                }
            }.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { !it })
        } finally {
            pool.shutdownNow()
        }
    }
    @Test fun `cache evicts oldest keys after 1000 entries`() {
        (0 until 1002).forEach { i ->
            nowMs = i.toLong()
            assertFalse(dedup.isDuplicate("addr-$i"))
        }
        assertFalse(dedup.isDuplicate("addr-0"))
    }
}
