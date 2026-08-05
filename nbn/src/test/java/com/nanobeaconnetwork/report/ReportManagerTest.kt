package com.nanobeaconnetwork.report

import com.google.gson.JsonParser
import com.nanobeaconnetwork.internal.api.ApiClient
import com.nanobeaconnetwork.internal.config.ServerConfigManager
import com.nanobeaconnetwork.internal.db.PendingReport
import com.nanobeaconnetwork.internal.db.PendingReportDao
import com.nanobeaconnetwork.internal.prefs.NbnPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReportManagerTest {
    private val server = MockWebServer()

    @Before fun setup() = server.start()
    @After fun teardown() = server.shutdown()

    private fun config(): ServerConfigManager {
        val prefs = mock<NbnPrefs>()
        whenever(prefs.reportMinIntervalSeconds).doReturn(0)
        whenever(prefs.reportBatchThreshold).doReturn(50)
        whenever(prefs.dedupWindowSeconds).doReturn(300)
        whenever(prefs.sourceMinIntervalSeconds).doReturn(300)
        return ServerConfigManager(prefs)
    }

    private fun apiClient(): ApiClient {
        val prefs = mock<NbnPrefs>()
        whenever(prefs.serverUrl).doReturn(server.url("/").toString().trimEnd('/'))
        whenever(prefs.anonymousToken).doReturn("token")
        return ApiClient(prefs, { null })
    }

    private fun manager(dao: FakeDao, now: () -> Long) =
        manager(dao, now, now)

    private fun manager(
        dao: FakeDao,
        wallNow: () -> Long,
        elapsedNow: () -> Long,
        anchor: String = "boot:1",
    ) =
        ReportManager(
            dao,
            apiClient(),
            config(),
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
            wallClockMs = wallNow,
            elapsedClockMs = elapsedNow,
            bootAnchor = { anchor },
        ) { 0.5 }

    private val validPayload = Base64.getEncoder().encodeToString(ByteArray(23) { it.toByte() })

    private suspend fun enqueue(
        manager: ReportManager,
        source: String = "mac:source-a",
        payload: String = validPayload,
        seenAt: String = "2026-01-01T00:00:00Z",
        latitude: Double? = 37.0,
        longitude: Double? = -122.0,
    ) = manager.enqueue(
        source, "0011223344556677", payload, -70, latitude, longitude,
        if (latitude == null) null else 10.0, if (latitude == null) "unknown" else "sdk_fused", false, seenAt,
    )

    private fun echoAccepted() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val json = JsonParser.parseString(request.body.clone().readUtf8()).asJsonObject
                val batchId = json.get("batch_id").asString
                return MockResponse().setResponseCode(202)
                    .setBody("""{"status":"accepted","batch_id":"$batchId"}""")
            }
        }
    }

    @Test fun `new observation atomically replaces pending latest for same source`() = runTest {
        val dao = FakeDao()
        val manager = manager(dao) { 1_000L }
        enqueue(manager, payload = validPayload)
        val first = dao.snapshot().single()
        enqueue(manager, payload = Base64.getEncoder().encodeToString(ByteArray(23) { 7 }))

        val latest = dao.snapshot().single()
        assertEquals(first.id, latest.id)
        assertNotEquals(first.observationId, latest.observationId)
        assertNotEquals(first.payloadBase64, latest.payloadBase64)
        assertEquals(1_000L, latest.createdElapsedRealtimeMs)
    }

    @Test fun `absent location queues and sends paired JSON nulls`() = runTest {
        val dao = FakeDao()
        val manager = manager(dao) { 1_000L }
        echoAccepted()

        assertEquals(EnqueueResult.Queued, enqueue(manager, latitude = null, longitude = null))
        assertEquals(null, dao.snapshot().single().latitude)
        assertEquals(null, dao.snapshot().single().longitude)
        manager.tryFlush()

        val report = JsonParser.parseString(server.takeRequest().body.readUtf8())
            .asJsonObject
            .getAsJsonArray("reports")[0]
            .asJsonObject
        assertTrue(report.get("latitude").isJsonNull)
        assertTrue(report.get("location_accuracy_m").isJsonNull)
        assertEquals("unknown", report.get("location_source").asString)
        assertFalse(report.get("location_is_mock").asBoolean)
        assertTrue(report.get("longitude").isJsonNull)
    }

    @Test fun `durable accept throttles that source until the interval elapses`() = runTest {
        val dao = FakeDao()
        var now = 1_000L
        val manager = manager(dao) { now }
        echoAccepted()

        assertFalse(manager.isThrottled("mac:source-a"))
        enqueue(manager)
        manager.tryFlush()

        assertTrue(manager.isThrottled("mac:source-a"))
        now += 299_999
        assertTrue(manager.isThrottled("mac:source-a"))
        now += 1
        assertFalse(manager.isThrottled("mac:source-a"))
    }

    @Test fun `throttle is per source and does not leak across sources`() = runTest {
        val dao = FakeDao()
        val manager = manager(dao) { 1_000L }
        echoAccepted()

        enqueue(manager, source = "mac:source-a")
        manager.tryFlush()

        assertTrue(manager.isThrottled("mac:source-a"))
        assertFalse(manager.isThrottled("mac:source-b"))
        assertFalse(manager.isThrottled("eid:0011223344556677"))
    }

    @Test fun `failed upload leaves the source unthrottled so retries stay free`() = runTest {
        val dao = FakeDao()
        val manager = manager(dao) { 1_000L }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }

        enqueue(manager)
        manager.tryFlush()

        assertFalse(manager.isThrottled("mac:source-a"))
    }

    @Test fun `throttled sightings are counted apart from the discard buckets`() = runTest {
        val dao = FakeDao()
        val manager = manager(dao) { 1_000L }

        manager.noteThrottled()
        manager.noteThrottled()

        val stats = manager.stats.value
        assertEquals(2, stats.throttledCount)
        assertEquals(0, stats.failedCount)
        assertEquals(0, stats.expiredCount)
        assertEquals(0, stats.invalidCount)
        assertEquals(0, stats.queueFullCount)
    }

    @Test fun `one missing coordinate is rejected before queueing`() = runTest {
        val dao = FakeDao()
        val manager = manager(dao) { 1_000L }

        assertEquals(EnqueueResult.Invalid, enqueue(manager, latitude = 31.0, longitude = null))
        assertEquals(EnqueueResult.Invalid, enqueue(manager, source = "mac:other", latitude = null, longitude = 121.0))
        assertTrue(dao.snapshot().isEmpty())
    }

    @Test fun `new data during HTTP keeps one in flight and one pending then drops failed old`() = runTest {
        val dao = FakeDao()
        var now = 1_000L
        val manager = manager(dao) { now }
        enqueue(manager)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                releaseResponse.await(2, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(500)
            }
        }

        val flush = async(Dispatchers.IO) { manager.tryFlush() }
        val request = async(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }.await()
        assertTrue(request != null)
        val newerPayload = Base64.getEncoder().encodeToString(ByteArray(23) { 9 })
        now += 10
        enqueue(manager, payload = newerPayload)
        assertEquals(setOf(PendingReport.SLOT_IN_FLIGHT, PendingReport.SLOT_PENDING), dao.snapshot().map { it.slot }.toSet())
        releaseResponse.countDown()
        flush.await()

        val remaining = dao.snapshot().single()
        assertEquals(PendingReport.SLOT_PENDING, remaining.slot)
        assertEquals(newerPayload, remaining.payloadBase64)
        assertEquals(0, remaining.failedAttempts)
    }

    @Test fun `matching 202 deletes batch and sends no source key or oracle fields`() = runTest {
        val dao = FakeDao()
        val manager = manager(dao) { 1_000L }
        echoAccepted()
        enqueue(manager)
        manager.tryFlush()

        assertTrue(dao.snapshot().isEmpty())
        assertEquals(1, manager.stats.value.todayReportCount)
        val request = server.takeRequest()
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(setOf("batch_id", "reports", "request_evidence"), json.keySet())
        assertFalse(json.has("source_key"))
        assertEquals(1, json.getAsJsonArray("reports").size())
    }

    @Test fun `mismatched 202 does not delete local data`() = runTest {
        val dao = FakeDao()
        var now = 1_000L
        val manager = manager(dao) { now }
        enqueue(manager)
        server.enqueue(MockResponse().setResponseCode(202)
            .setBody("""{"status":"accepted","batch_id":"550e8400-e29b-41d4-a716-446655440000"}"""))
        manager.tryFlush()

        assertEquals(1, dao.snapshot().size)
        assertEquals(1, dao.snapshot().single().failedAttempts)
        now = dao.snapshot().single().nextAttemptElapsedRealtimeMs
    }

    @Test fun `429 and 503 preserve ordinary failure budget and honor retry after`() = runTest {
        for (code in listOf(429, 503)) {
            val dao = FakeDao()
            val manager = manager(dao) { 10_000L }
            enqueue(manager, source = "mac:$code")
            server.enqueue(MockResponse().setResponseCode(code).addHeader("Retry-After", "120"))
            manager.tryFlush()

            val row = dao.snapshot().single()
            assertEquals(0, row.failedAttempts)
            assertEquals(130_000L, row.nextAttemptElapsedRealtimeMs)
            assertTrue(manager.stats.value.rateLimited)
        }
    }

    @Test fun `batch id remains stable across an ordinary retry`() = runTest {
        val dao = FakeDao()
        var now = 100_000L
        val manager = manager(dao) { now }
        enqueue(manager)
        server.enqueue(MockResponse().setResponseCode(500))
        manager.tryFlush()
        val first = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            .get("batch_id").asString

        now = dao.snapshot().single().nextAttemptElapsedRealtimeMs
        echoAccepted()
        manager.tryFlush()
        val second = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            .get("batch_id").asString
        assertEquals(first, second)
    }

    @Test fun `restart preserves exact in flight batch id for idempotent retry`() = runTest {
        val dao = FakeDao()
        val fixedBatchId = UUID.randomUUID().toString()
        dao.upsertLatest(
            PendingReport(
                observationId = UUID.randomUUID().toString(),
                sourceKey = "mac:restart",
                slot = PendingReport.SLOT_IN_FLIGHT,
                batchId = fixedBatchId,
                eidHex = "0011223344556677",
                payloadBase64 = validPayload,
                rssi = -70,
                latitude = 37.0,
                longitude = -122.0,
                locationAccuracyMeters = 10.0,
                locationSource = "sdk_fused",
                locationIsMock = false,
                clientSeenAt = "2026-01-01T00:00:00Z",
                createdElapsedRealtimeMs = 1_000L,
                createdWallTimeMs = 1_000L,
                bootAnchor = "boot:1",
                expiresElapsedRealtimeMs = 301_000L,
                nextAttemptElapsedRealtimeMs = 1_000L,
            ),
        )
        val manager = manager(dao) { 2_000L }
        echoAccepted()
        manager.tryFlush()

        val request = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals(fixedBatchId, request.get("batch_id").asString)
        assertTrue(dao.snapshot().isEmpty())
    }

    @Test fun `restart drops superseded in flight row and sends pending latest`() = runTest {
        val dao = FakeDao()
        val oldPayload = Base64.getEncoder().encodeToString(ByteArray(23) { 3 })
        val newPayload = Base64.getEncoder().encodeToString(ByteArray(23) { 4 })
        dao.upsertLatest(
            PendingReport(
                observationId = UUID.randomUUID().toString(), sourceKey = "mac:restart-newer",
                slot = PendingReport.SLOT_IN_FLIGHT, batchId = UUID.randomUUID().toString(),
                eidHex = "0011223344556677", payloadBase64 = oldPayload, rssi = -70,
                latitude = 37.0, longitude = -122.0, clientSeenAt = "2026-01-01T00:00:00Z",
                locationAccuracyMeters = 10.0, locationSource = "sdk_fused", locationIsMock = false,
                createdElapsedRealtimeMs = 1_000L, createdWallTimeMs = 1_000L,
                bootAnchor = "boot:1", expiresElapsedRealtimeMs = 301_000L,
                nextAttemptElapsedRealtimeMs = 1_000L,
            ),
        )
        dao.upsertLatest(
            PendingReport(
                observationId = UUID.randomUUID().toString(), sourceKey = "mac:restart-newer",
                eidHex = "0011223344556677", payloadBase64 = newPayload, rssi = -69,
                latitude = 38.0, longitude = -121.0, clientSeenAt = "2026-01-01T00:00:01Z",
                locationAccuracyMeters = 10.0, locationSource = "sdk_fused", locationIsMock = false,
                createdElapsedRealtimeMs = 1_500L, createdWallTimeMs = 1_500L,
                bootAnchor = "boot:1", expiresElapsedRealtimeMs = 301_500L,
                nextAttemptElapsedRealtimeMs = 1_500L,
            ),
        )
        val manager = manager(dao) { 2_000L }
        echoAccepted()
        manager.tryFlush()

        val request = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        val sentPayload = request.getAsJsonArray("reports")[0].asJsonObject.get("payload").asString
        assertEquals(newPayload, sentPayload)
        assertTrue(dao.snapshot().isEmpty())
    }

    @Test fun `ordinary failures retain observation until five minute hard deadline`() = runTest {
        val dao = FakeDao()
        var now = 100_000L
        val manager = manager(dao) { now }
        enqueue(manager)
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(500))
            manager.tryFlush()
            dao.snapshot().firstOrNull()?.let { row -> now = maxOf(row.nextAttemptElapsedRealtimeMs, now + 60_000) }
        }
        assertEquals(1, dao.snapshot().size)
        assertEquals(2, dao.snapshot().single().failedAttempts)
        assertEquals(0, manager.stats.value.failedCount)

        now = 400_000L
        manager.tryFlush()
        assertTrue(dao.snapshot().isEmpty())
        assertEquals(1, manager.stats.value.expiredCount)
        assertEquals(2, server.requestCount)
    }

    @Test fun `five minute expiry deletes unsent latest observation`() = runTest {
        val dao = FakeDao()
        var now = 5_000L
        val manager = manager(dao) { now }
        enqueue(manager)
        now += 5L * 60 * 1_000
        manager.tryFlush()

        assertTrue(dao.snapshot().isEmpty())
        assertEquals(1, manager.stats.value.expiredCount)
        assertEquals(0, server.requestCount)
    }


    @Test fun `wall clock jumps do not age or repackage an observation`() = runTest {
        val dao = FakeDao()
        var wallNow = 1_000_000L
        var elapsedNow = 5_000L
        val manager = manager(dao, { wallNow }, { elapsedNow })
        enqueue(manager)
        val observationID = dao.snapshot().single().observationId

        wallNow += 30L * 24 * 60 * 60 * 1_000
        elapsedNow += 1_000L
        echoAccepted()
        manager.tryFlush()

        val sent = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            .getAsJsonArray("reports")[0].asJsonObject
        assertEquals(observationID, sent.get("observation_id").asString)
        assertTrue(dao.snapshot().isEmpty())
    }

    @Test fun `unverifiable prior boot rows are discarded without upload`() = runTest {
        val dao = FakeDao()
        var elapsedNow = 10_000L
        val firstBoot = manager(dao, { 100_000L }, { elapsedNow }, "boot:1")
        enqueue(firstBoot)
        assertEquals("boot:1", dao.snapshot().single().bootAnchor)

        elapsedNow = 100L
        val secondBoot = manager(dao, { 200_000L }, { elapsedNow }, "boot:2")
        secondBoot.tryFlush()

        assertTrue(dao.snapshot().isEmpty())
        assertEquals(1, secondBoot.stats.value.expiredCount)
        assertEquals(0, server.requestCount)
    }

    @Test fun `rows from a previous process of the same boot are discarded without upload`() = runTest {
        val dao = FakeDao()
        // Same boot counter, different process UUID — the anchor BootSessionClock produces.
        val firstRun = manager(dao, { 100_000L }, { 10_000L }, "boot:7|proc:aaa")
        enqueue(firstRun)
        assertEquals(1, dao.snapshot().size)
        echoAccepted()

        val secondRun = manager(dao, { 200_000L }, { 11_000L }, "boot:7|proc:bbb")
        secondRun.tryFlush()

        assertTrue(dao.snapshot().isEmpty())
        assertEquals(0, server.requestCount)
        // Never scanned by this process, so it must not be counted as reported either.
        assertEquals(0, secondRun.stats.value.todayReportCount)
        assertEquals(0, secondRun.stats.value.todayScanCount)
    }

    @Test fun `rows enqueued by this process survive the startup purge`() = runTest {
        val dao = FakeDao()
        val manager = manager(dao, { 100_000L }, { 10_000L }, "boot:7|proc:aaa")
        echoAccepted()

        enqueue(manager)
        manager.tryFlush()

        assertEquals(1, server.requestCount)
        assertEquals(1, manager.stats.value.todayScanCount)
        assertEquals(1, manager.stats.value.todayReportCount)
        assertEquals(0, manager.stats.value.expiredCount)
    }

    @Test fun `full queue returns QueueFull without deleting another source`() = runTest {
        val dao = FakeDao(baseCount = 50_000)
        val manager = manager(dao) { 1_000L }
        val result = enqueue(manager)

        assertEquals(EnqueueResult.QueueFull, result)
        assertTrue(dao.snapshot().isEmpty())
        assertEquals(1, manager.stats.value.queueFullCount)
    }
}

private class FakeDao(
    private val baseCount: Int = 0,
    private val baseBytes: Long = 0,
) : PendingReportDao {
    private val rows = mutableListOf<PendingReport>()
    private var nextId = 1L

    @Synchronized fun snapshot(): List<PendingReport> = rows.map { it.copy() }

    override suspend fun upsertLatest(report: PendingReport): Long {
        rows.removeAll { it.id == report.id || (it.sourceKey == report.sourceKey && it.slot == report.slot) }
        val id = if (report.id == 0L) nextId++ else report.id
        rows += report.copy(id = id)
        return id
    }

    override suspend fun findPending(sourceKey: String) =
        rows.firstOrNull { it.sourceKey == sourceKey && it.slot == PendingReport.SLOT_PENDING }

    override suspend fun hasPending(sourceKey: String) =
        rows.count { it.sourceKey == sourceKey && it.slot == PendingReport.SLOT_PENDING }

    override suspend fun fetchReady(now: Long, limit: Int) = rows
        .filter { it.slot == PendingReport.SLOT_PENDING && it.nextAttemptElapsedRealtimeMs <= now }
        .sortedWith(compareBy<PendingReport> { it.createdElapsedRealtimeMs }.thenBy { it.id }).take(limit)

    override suspend fun fetchReadyBatch(batchId: String, now: Long, limit: Int) = rows
        .filter { it.slot == PendingReport.SLOT_PENDING && it.batchId == batchId && it.nextAttemptElapsedRealtimeMs <= now }
        .sortedWith(compareBy<PendingReport> { it.createdElapsedRealtimeMs }.thenBy { it.id }).take(limit)

    override suspend fun markInFlight(ids: List<Long>, batchId: String): Int {
        var changed = 0
        for (index in rows.indices) if (rows[index].id in ids && rows[index].slot == PendingReport.SLOT_PENDING) {
            rows[index] = rows[index].copy(slot = PendingReport.SLOT_IN_FLIGHT, batchId = batchId)
            changed++
        }
        return changed
    }

    override suspend fun fetchInFlightBatch(batchId: String) =
        rows.filter { it.slot == PendingReport.SLOT_IN_FLIGHT && it.batchId == batchId }

    override suspend fun fetchAllInFlight() =
        rows.filter { it.slot == PendingReport.SLOT_IN_FLIGHT }

    override suspend fun requeue(
        id: Long,
        batchId: String?,
        failedAttempts: Int,
        nextAttemptAt: Long,
    ): Int {
        val index = rows.indexOfFirst { it.id == id && it.slot == PendingReport.SLOT_IN_FLIGHT }
        if (index < 0) return 0
        rows[index] = rows[index].copy(
            slot = PendingReport.SLOT_PENDING,
            batchId = batchId,
            failedAttempts = failedAttempts,
            nextAttemptElapsedRealtimeMs = nextAttemptAt,
        )
        return 1
    }

    override suspend fun clearPendingBatchIds(batchIds: List<String>) {
        for (index in rows.indices) if (rows[index].slot == PendingReport.SLOT_PENDING && rows[index].batchId in batchIds) {
            rows[index] = rows[index].copy(batchId = null)
        }
    }

    override suspend fun fetchExpired(now: Long) = rows.filter { it.expiresElapsedRealtimeMs <= now }

    override suspend fun fetchFromOtherBoots(bootAnchor: String) =
        rows.filter { it.bootAnchor != bootAnchor }

    override suspend fun deleteByIds(ids: List<Long>): Int {
        val before = rows.size
        rows.removeAll { it.id in ids }
        return before - rows.size
    }

    override suspend fun count() = baseCount + rows.size
    override suspend fun estimatedBytes() = baseBytes + rows.sumOf { it.estimatedBytes() }
}
