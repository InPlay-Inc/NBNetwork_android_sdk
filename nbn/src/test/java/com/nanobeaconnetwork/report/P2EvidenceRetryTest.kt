package com.nanobeaconnetwork.report

import com.google.gson.JsonParser
import com.nanobeaconnetwork.internal.api.ApiClient
import com.nanobeaconnetwork.internal.api.RequestEvidence
import com.nanobeaconnetwork.internal.config.ServerConfigManager
import com.nanobeaconnetwork.internal.prefs.NbnPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class P2EvidenceRetryTest {
    private val server = MockWebServer()

    @Before fun setup() = server.start()
    @After fun teardown() = server.shutdown()

    @Test fun `retry preserves business request and refreshes evidence`() = runTest {
        val dao = FakeDaoForP2()
        val prefs = mock<NbnPrefs>()
        whenever(prefs.serverUrl).doReturn(server.url("/").toString().trimEnd('/'))
        whenever(prefs.anonymousToken).doReturn("token")
        val configPrefs = mock<NbnPrefs>()
        whenever(configPrefs.reportMinIntervalSeconds).doReturn(0)
        whenever(configPrefs.reportBatchThreshold).doReturn(50)
        whenever(configPrefs.dedupWindowSeconds).doReturn(300)
        whenever(configPrefs.sourceMinIntervalSeconds).doReturn(300)
        val signatures = AtomicInteger()
        var now = 1_000L
        val manager = ReportManager(
            dao = dao,
            apiClient = ApiClient(prefs, { "new-token" }),
            configManager = ServerConfigManager(configPrefs),
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
            wallClockMs = { now },
            elapsedClockMs = { now },
            bootAnchor = { "boot:1" },
            evidenceProvider = { RequestEvidence("key-1", "signature-${signatures.incrementAndGet()}") },
            randomUnit = { 0.5 },
        )
        val attempts = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (attempts.incrementAndGet() == 1) {
                    return MockResponse().setResponseCode(401)
                }
                val batchId = JsonParser.parseString(request.body.clone().readUtf8())
                    .asJsonObject.get("batch_id").asString
                return MockResponse().setResponseCode(202)
                    .setBody("""{"status":"accepted","batch_id":"$batchId"}""")
            }
        }

        val payload = Base64.getEncoder().encodeToString(ByteArray(23) { it.toByte() })
        assertEquals(
            EnqueueResult.Queued,
            manager.enqueue(
                "source", "0011223344556677", payload, -70, 37.0, -122.0,
                10.0, "sdk_fused", false, "2026-01-01T00:00:00Z",
            ),
        )
        manager.tryFlush()
        val first = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject

        now = dao.snapshot().single().nextAttemptElapsedRealtimeMs
        manager.tryFlush()
        val second = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject

        assertEquals(first.get("batch_id"), second.get("batch_id"))
        assertEquals(first.get("reports"), second.get("reports"))
        assertNotEquals(
            first.getAsJsonObject("request_evidence").get("installation_signature"),
            second.getAsJsonObject("request_evidence").get("installation_signature"),
        )
        assertTrue(dao.snapshot().isEmpty())
    }
}

private class FakeDaoForP2 : com.nanobeaconnetwork.internal.db.PendingReportDao {
    private val rows = mutableListOf<com.nanobeaconnetwork.internal.db.PendingReport>()
    private var nextID = 1L

    @Synchronized fun snapshot() = rows.map { it.copy() }
    override suspend fun count() = rows.size
    override suspend fun estimatedBytes() = rows.sumOf { it.estimatedBytes() }
    override suspend fun findPending(sourceKey: String) = rows.firstOrNull {
        it.sourceKey == sourceKey && it.slot == com.nanobeaconnetwork.internal.db.PendingReport.SLOT_PENDING
    }
    override suspend fun upsertLatest(report: com.nanobeaconnetwork.internal.db.PendingReport): Long {
        val index = rows.indexOfFirst { it.sourceKey == report.sourceKey && it.slot == report.slot }
        val stored = report.copy(id = if (index >= 0) rows[index].id else nextID++)
        if (index >= 0) rows[index] = stored else rows += stored
        return stored.id
    }
    override suspend fun fetchReady(now: Long, limit: Int) = rows.filter {
        it.slot == com.nanobeaconnetwork.internal.db.PendingReport.SLOT_PENDING &&
            it.nextAttemptElapsedRealtimeMs <= now
    }.take(limit)
    override suspend fun fetchReadyBatch(batchId: String, now: Long, limit: Int) = rows.filter {
        it.batchId == batchId && it.slot == com.nanobeaconnetwork.internal.db.PendingReport.SLOT_PENDING &&
            it.nextAttemptElapsedRealtimeMs <= now
    }.take(limit)
    override suspend fun markInFlight(ids: List<Long>, batchId: String): Int {
        var changed = 0
        rows.indices.forEach { index ->
            if (rows[index].id in ids) {
                rows[index] = rows[index].copy(
                    slot = com.nanobeaconnetwork.internal.db.PendingReport.SLOT_IN_FLIGHT,
                    batchId = batchId,
                )
                changed++
            }
        }
        return changed
    }
    override suspend fun fetchAllInFlight() = rows.filter {
        it.slot == com.nanobeaconnetwork.internal.db.PendingReport.SLOT_IN_FLIGHT
    }
    override suspend fun fetchInFlightBatch(batchId: String) = rows.filter {
        it.batchId == batchId && it.slot == com.nanobeaconnetwork.internal.db.PendingReport.SLOT_IN_FLIGHT
    }
    override suspend fun fetchExpired(now: Long) = rows.filter { it.expiresElapsedRealtimeMs <= now }
    override suspend fun fetchFromOtherBoots(bootAnchor: String) = rows.filter { it.bootAnchor != bootAnchor }
    override suspend fun hasPending(sourceKey: String) = rows.count {
        it.sourceKey == sourceKey && it.slot == com.nanobeaconnetwork.internal.db.PendingReport.SLOT_PENDING
    }
    override suspend fun requeue(id: Long, batchId: String?, failedAttempts: Int, nextAttemptAt: Long): Int {
        val index = rows.indexOfFirst { it.id == id }
        rows[index] = rows[index].copy(
            slot = com.nanobeaconnetwork.internal.db.PendingReport.SLOT_PENDING,
            batchId = batchId,
            failedAttempts = failedAttempts,
            nextAttemptElapsedRealtimeMs = nextAttemptAt,
        )
        return 1
    }
    override suspend fun deleteByIds(ids: List<Long>): Int {
        return rows.removeAll { it.id in ids }.let { if (it) ids.size else 0 }
    }
    override suspend fun clearPendingBatchIds(batchIds: List<String>) {
        rows.indices.forEach { index ->
            if (rows[index].batchId in batchIds) rows[index] = rows[index].copy(batchId = null)
        }
    }
}
