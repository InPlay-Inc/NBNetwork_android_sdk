package com.nanobeaconnetwork.report

import com.nanobeaconnetwork.internal.api.ApiClient
import com.nanobeaconnetwork.internal.config.ServerConfigManager
import com.nanobeaconnetwork.internal.db.PendingReport
import com.nanobeaconnetwork.internal.db.PendingReportDao
import com.nanobeaconnetwork.internal.prefs.SdkPrefs
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ReportManagerTest {

    private val mockServer = MockWebServer()

    @Before fun setup() { mockServer.start() }
    @After fun teardown() { mockServer.shutdown() }

    private fun makeConfig(minIntervalSecs: Int = 0, batchThreshold: Int = 50): ServerConfigManager {
        val prefs = mock<SdkPrefs>()
        whenever(prefs.reportMinIntervalSeconds).doReturn(minIntervalSecs)
        whenever(prefs.reportBatchThreshold).doReturn(batchThreshold)
        whenever(prefs.dedupWindowSeconds).doReturn(300)
        return ServerConfigManager(prefs)
    }

    private fun makeApiClient(): ApiClient {
        val prefs = mock<SdkPrefs>()
        val baseUrl = mockServer.url("/").toString()
        whenever(prefs.serverUrl).doReturn(baseUrl.trimEnd('/'))
        whenever(prefs.anonymousToken).doReturn("")
        return ApiClient(prefs, { null })
    }

    private fun makeReport(id: Long = 1L, eid: String = "aabbccdd00112233"): PendingReport =
        PendingReport(
            id = id, eidHex = eid, payloadHex = "deadbeef0102030405060708090a",
            rssi = -70, latitude = 37.0, longitude = -122.0, timestamp = "2026-01-01T00:00:00Z"
        )

    // ---- R01: enqueue inserts into DAO ---------------------------------------

    @Test fun `R01 enqueue inserts record into DAO`() = runTest {
        val dao = mock<PendingReportDao>()
        whenever(dao.count()).doReturn(0)

        val manager = ReportManager(dao, makeApiClient(), makeConfig(), this)

        manager.enqueue("aabbccdd00112233", "deadbeef", -70, 37.0, -122.0, "2026-01-01T00:00:00Z")

        verify(dao).upsert(any())
    }

    // ---- R01b: flush sends HTTP batch request --------------------------------

    @Test fun `R01b flush sends batch report to server`() = runTest {
        val dao = mock<PendingReportDao>()
        whenever(dao.count()).doReturn(1)
        whenever(dao.fetchBatch(500)).doReturn(listOf(makeReport()))

        mockServer.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"accepted":1,"total":1}"""))

        val manager = ReportManager(dao, makeApiClient(), makeConfig(), this)
        manager.tryFlush()

        assertEquals(1, mockServer.requestCount)
        verify(dao).deleteByIds(listOf(1L))
    }

    @Test fun `R01c server-dropped batch is cleared not retried`() = runTest {
        // A 2xx with count=0 means the server received the batch but stored/deduped nothing
        // (e.g. dropped for an unknown EID). Per the dedup requirement, dropped data must NOT be
        // resent — the batch is cleared and left to the client dedup window, not retried.
        val dao = mock<PendingReportDao>()
        whenever(dao.count()).doReturn(1)
        whenever(dao.fetchBatch(500)).doReturn(listOf(makeReport()))

        mockServer.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"status":"ok","count":0}"""))

        val manager = ReportManager(dao, makeApiClient(), makeConfig(), this)
        manager.tryFlush()

        verify(dao).deleteByIds(listOf(1L))
        verify(dao, never()).incrementRetries(any())
    }

    // ---- R03: queue limit enforcement ----------------------------------------

    @Test fun `R03 deletes oldest records when queue exceeds 10000`() = runTest {
        val dao = mock<PendingReportDao>()
        whenever(dao.count()).doReturn(10001)
        whenever(dao.fetchBatch(any())).doReturn(emptyList()) // prevent NPE in triggered flush

        val manager = ReportManager(dao, makeApiClient(), makeConfig(), this)
        manager.enqueue("aa", "bb", -70, 0.0, 0.0, "ts")

        verify(dao).deleteOldest(2) // 10001 - 10000 + 1 = 2
    }

    // ---- R04: 429 sets rateLimited flag and blocks flushing ------------------

    @Test fun `R04 429 response sets rateLimited in stats and pauses reporting`() = runTest {
        val dao = mock<PendingReportDao>()
        whenever(dao.count()).doReturn(1)
        whenever(dao.fetchBatch(500)).doReturn(listOf(makeReport()))

        mockServer.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "60"))

        val manager = ReportManager(dao, makeApiClient(), makeConfig(), this)
        manager.tryFlush()  // first flush → 429

        assertTrue(manager.stats.value.rateLimited)

        // Second flush within the 60s retry window should be skipped
        mockServer.enqueue(MockResponse().setResponseCode(200))
        manager.tryFlush()

        assertEquals(1, mockServer.requestCount)  // only 1 real HTTP call
    }

    // ---- R05: network error triggers exponential backoff ---------------------

    @Test fun `R05 network error triggers backoff preventing immediate retry`() = runTest {
        val dao = mock<PendingReportDao>()
        whenever(dao.count()).doReturn(1)
        whenever(dao.fetchBatch(500)).doReturn(listOf(makeReport()))

        mockServer.enqueue(MockResponse().setResponseCode(500))

        val manager = ReportManager(dao, makeApiClient(), makeConfig(), this)
        manager.tryFlush()  // flush → 500 error → backoff 1s set

        // Immediate retry within backoff window should be skipped
        mockServer.enqueue(MockResponse().setResponseCode(200))
        manager.tryFlush()  // should return early due to backoff

        assertEquals(1, mockServer.requestCount)
    }

    // ---- R08: success after failure resets backoff ---------------------------

    @Test fun `R08 successful report resets backoff to 1 second`() = runTest {
        val dao = mock<PendingReportDao>()
        whenever(dao.count()).doReturn(1)
        whenever(dao.fetchBatch(500)).doReturn(listOf(makeReport()))

        mockServer.enqueue(MockResponse().setResponseCode(500))  // first: fail
        mockServer.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"accepted":1,"total":1}"""))  // second: succeed

        val manager = ReportManager(dao, makeApiClient(), makeConfig(), this)
        manager.tryFlush()  // flush → 500 → backoff 1s

        Thread.sleep(1100)  // wait for real 1s backoff to expire

        manager.tryFlush()  // after backoff, flush → 200 → reset backoff

        assertEquals(2, mockServer.requestCount)
        assertFalse(manager.stats.value.rateLimited)
    }

    // ---- R09: stats updated correctly ----------------------------------------

    @Test fun `R09 stats todayScanCount increments on enqueue`() = runTest {
        val dao = mock<PendingReportDao>()
        whenever(dao.count()).doReturn(0)

        val manager = ReportManager(dao, makeApiClient(), makeConfig(), this)

        manager.enqueue("aa", "bb", -70, 0.0, 0.0, "ts1")
        manager.enqueue("cc", "dd", -80, 0.0, 0.0, "ts2")

        assertEquals(2, manager.stats.value.todayScanCount)
        assertEquals(0, manager.stats.value.todayReportCount)
    }
}
