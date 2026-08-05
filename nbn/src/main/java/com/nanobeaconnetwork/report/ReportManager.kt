package com.nanobeaconnetwork.report

import android.util.Log
import com.nanobeaconnetwork.internal.api.ApiClient
import com.nanobeaconnetwork.internal.api.BatchReportRequest
import com.nanobeaconnetwork.internal.api.ReportItem
import com.nanobeaconnetwork.internal.api.RequestEvidence
import com.nanobeaconnetwork.internal.config.ServerConfigManager
import com.nanobeaconnetwork.internal.db.PendingReport
import com.nanobeaconnetwork.internal.db.PendingReportDao
import com.nanobeaconnetwork.model.ReportStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

internal data class FlushResult(val eidPrefixes: List<String>)

internal enum class EnqueueResult { Queued, QueueFull, Invalid }

private const val TAG = "ReportManager"
private const val MAX_QUEUE_ITEMS = 50_000
private const val MAX_QUEUE_BYTES = 50L * 1024 * 1024
private const val BATCH_SIZE = 50
private const val RETENTION_MS = 5L * 60 * 1000
private const val MAX_ACCEPTED_SOURCES = 2_000
private const val MAX_RETRY_AFTER_SECONDS = 86_400L
private val RETRY_OFFSETS_MS = longArrayOf(60_000, 180_000, 420_000, 900_000, 1_800_000)

internal class ReportManager(
    private val dao: PendingReportDao,
    private val apiClient: ApiClient,
    private val configManager: ServerConfigManager,
    private val scope: CoroutineScope,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val elapsedClockMs: () -> Long,
    private val bootAnchor: () -> String,
    private val evidenceProvider: suspend (BatchReportRequest) -> RequestEvidence? = { null },
    private val randomUnit: () -> Double = { Random.nextDouble() },
) {
    private val _stats = MutableStateFlow(ReportStats())
    val stats: StateFlow<ReportStats> = _stats.asStateFlow()

    private val _flushResults = MutableSharedFlow<FlushResult>(extraBufferCapacity = 64)
    val flushResults: SharedFlow<FlushResult> = _flushResults.asSharedFlow()

    private val flushMutex = Mutex()
    private val queueMutex = Mutex()
    private val retryAfterMs = AtomicLong(0)
    private var globalBackoffMs = 1_000L
    private var lastFlushMs = 0L
    private var recovered = false
    private var forceSingleItem = false
    private val todayScan = AtomicInteger(0)
    private var bootChecked = false
    private val todayAccepted = AtomicInteger(0)
    private val failed = AtomicInteger(0)
    private val expired = AtomicInteger(0)
    private val invalid = AtomicInteger(0)
    private val queueFull = AtomicInteger(0)
    private val throttled = AtomicInteger(0)
    private var flushJob: Job? = null

    /**
     * sourceKey -> elapsed-realtime of the last durable server accept for that source. Backs the
     * [isThrottled] gate. In-memory by design: the throttle is a client-side upload budget, not a
     * correctness guarantee, so a process restart resets it.
     */
    private val lastAcceptedBySource = ConcurrentHashMap<String, Long>()

    fun start() {
        flushJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                tryFlush()
            }
        }
    }

    fun stop() {
        flushJob?.cancel()
    }

    /**
     * True when [sourceKey] was durably accepted by the server less than
     * [ServerConfigManager.sourceMinIntervalMs] ago, so this sighting must not be uploaded.
     *
     * Only a successful 202 arms the gate. A source whose uploads keep failing is never throttled —
     * it has not been reported yet, so retries must stay free to run.
     */
    fun isThrottled(sourceKey: String): Boolean {
        val last = lastAcceptedBySource[sourceKey] ?: return false
        return elapsedClockMs() - last < configManager.sourceMinIntervalMs
    }

    /** Records a sighting dropped by [isThrottled] so the caller's UI can report it. */
    suspend fun noteThrottled() {
        throttled.incrementAndGet()
        updateStats()
    }

    suspend fun enqueue(
        sourceKey: String,
        eidHex: String,
        payloadBase64: String,
        rssi: Int,
        latitude: Double?,
        longitude: Double?,
        locationAccuracyMeters: Double?,
        locationSource: String,
        locationIsMock: Boolean,
        clientSeenAt: String,
    ): EnqueueResult = queueMutex.withLock {
        todayScan.incrementAndGet()
        if (!isLocallyValid(
                eidHex,
                payloadBase64,
                latitude,
                longitude,
                locationAccuracyMeters,
                locationSource,
                locationIsMock,
                clientSeenAt,
            )
        ) {
            invalid.incrementAndGet()
            updateStats()
            return@withLock EnqueueResult.Invalid
        }

        val nowElapsed = elapsedClockMs()
        discardRowsFromPreviousSessions()
        val previous = dao.findPending(sourceKey)
        val next = PendingReport(
            id = previous?.id ?: 0,
            observationId = UUID.randomUUID().toString(),
            sourceKey = sourceKey,
            eidHex = eidHex.lowercase(),
            payloadBase64 = payloadBase64,
            rssi = rssi,
            latitude = latitude,
            longitude = longitude,
            locationAccuracyMeters = locationAccuracyMeters,
            locationSource = locationSource,
            locationIsMock = locationIsMock,
            clientSeenAt = clientSeenAt,
            createdElapsedRealtimeMs = nowElapsed,
            createdWallTimeMs = wallClockMs(),
            bootAnchor = bootAnchor(),
            expiresElapsedRealtimeMs = nowElapsed + RETENTION_MS,
            nextAttemptElapsedRealtimeMs = nowElapsed,
        )
        val projectedCount = dao.count() + if (previous == null) 1 else 0
        val projectedBytes = dao.estimatedBytes() - (previous?.estimatedBytes() ?: 0) + next.estimatedBytes()
        if (projectedCount > MAX_QUEUE_ITEMS || projectedBytes > MAX_QUEUE_BYTES) {
            queueFull.incrementAndGet()
            updateStats()
            return@withLock EnqueueResult.QueueFull
        }
        dao.upsertLatest(next)
        updateStats()
        if (dao.count() >= configManager.batchThreshold) scope.launch { tryFlush() }
        EnqueueResult.Queued
    }

    internal suspend fun tryFlush() = flushMutex.withLock { tryFlushLocked() }

    private suspend fun tryFlushLocked() {
        val now = elapsedClockMs()
        queueMutex.withLock {
            if (!recovered) {
                discardRowsFromPreviousSessions()
                recoverInterruptedRequests(now)
                recovered = true
            }
            removeExpired(now)
        }
        if (now < retryAfterMs.get() || now - lastFlushMs < configManager.reportMinIntervalMs) {
            updateStats()
            return
        }

        val pending = queueMutex.withLock { claimBatch(now) }
        if (pending.isEmpty()) {
            updateStats()
            return
        }
        val batchId = requireNotNull(pending.first().batchId)
        val businessRequest = BatchReportRequest(
            batchId = batchId,
            reports = pending.map {
                ReportItem(
                    observationId = it.observationId,
                    payload = it.payloadBase64,
                    rssi = it.rssi,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    clientSeenAt = it.clientSeenAt,
                    locationAccuracyMeters = it.locationAccuracyMeters,
                    locationSource = it.locationSource,
                    locationIsMock = it.locationIsMock,
                )
            },
        )

        try {
            val request = businessRequest.copy(requestEvidence = evidenceProvider(businessRequest))
            val response = apiClient.service.batchReport(request)
            handleResponse(response, pending, batchId, now)
        } catch (e: Exception) {
            queueMutex.withLock { releaseBatch(pending, countedFailure = true, preserveBatch = true, now = now) }
            scheduleGlobalBackoff(now, minimumMs = 1_000L)
            Log.w(TAG, "Report transport failed: ${e.javaClass.simpleName}")
        }
        updateStats()
    }

    private suspend fun handleResponse(
        response: Response<com.nanobeaconnetwork.internal.api.BatchReportResponse>,
        pending: List<PendingReport>,
        batchId: String,
        now: Long,
    ) {
        val body = response.body()
        if (response.code() == 202 && body?.status == "accepted" && body.batchId == batchId) {
            queueMutex.withLock { dao.deleteByIds(pending.map { it.id }) }
            todayAccepted.addAndGet(pending.size)
            pending.forEach { lastAcceptedBySource[it.sourceKey] = now }
            pruneAcceptedSources(now)
            _flushResults.tryEmit(FlushResult(pending.map { it.eidHex.take(8) }))
            retryAfterMs.set(0)
            globalBackoffMs = 1_000L
            lastFlushMs = now
            forceSingleItem = false
            Log.d(TAG, "Server durably accepted ${pending.size} observations")
            return
        }

        when (response.code()) {
            429, 503 -> {
                val seconds = response.headers()["Retry-After"]?.toLongOrNull()
                    ?.coerceIn(1, MAX_RETRY_AFTER_SECONDS) ?: 60L
                queueMutex.withLock {
                    releaseBatch(
                        pending,
                        countedFailure = false,
                        preserveBatch = true,
                        now = now,
                        fixedNextAt = now + seconds * 1_000,
                    )
                }
                scheduleGlobalBackoff(now, minimumMs = seconds * 1_000)
                if (response.code() == 429) applyConfigFromErrorBody(response)
            }
            400, 413 -> {
                if (pending.size > 1) {
                    forceSingleItem = true
                    queueMutex.withLock {
                        releaseBatch(pending, countedFailure = false, preserveBatch = false, now = now)
                    }
                } else {
                    queueMutex.withLock { dao.deleteByIds(pending.map { it.id }) }
                    invalid.addAndGet(pending.size)
                }
            }
            else -> {
                queueMutex.withLock {
                    releaseBatch(
                        pending,
                        countedFailure = true,
                        preserveBatch = response.code() != 409,
                        now = now,
                    )
                }
                scheduleGlobalBackoff(now, minimumMs = 1_000L)
            }
        }
        Log.w(TAG, "Report endpoint returned HTTP ${response.code()}")
    }

    private suspend fun claimBatch(now: Long): List<PendingReport> {
        val first = dao.fetchReady(now, 1).firstOrNull() ?: return emptyList()
        val limit = if (forceSingleItem) 1 else BATCH_SIZE
        val selected = if (first.batchId != null) {
            dao.fetchReadyBatch(first.batchId, now, limit)
        } else {
            dao.fetchReady(now, limit).filter { it.batchId == null }
        }
        if (selected.isEmpty()) return emptyList()
        val batchId = first.batchId ?: UUID.randomUUID().toString()
        val changed = dao.markInFlight(selected.map { it.id }, batchId)
        if (changed != selected.size) {
            // Another local queue operation won the race; retry on the next scheduler tick.
            return emptyList()
        }
        return selected.map { it.copy(slot = PendingReport.SLOT_IN_FLIGHT, batchId = batchId) }
    }

    private suspend fun releaseBatch(
        rows: List<PendingReport>,
        countedFailure: Boolean,
        preserveBatch: Boolean,
        now: Long,
        fixedNextAt: Long? = null,
    ) {
        var contentChanged = !preserveBatch
        val survivors = mutableListOf<Pair<PendingReport, Int>>()
        for (row in rows) {
            if (dao.hasPending(row.sourceKey) > 0) {
                dao.deleteByIds(listOf(row.id))
                contentChanged = true
                continue
            }
            val failures = row.failedAttempts + if (countedFailure) 1 else 0
            survivors += row to failures
        }
        val commonNextAt = fixedNextAt ?: survivors.maxOfOrNull { (row, failures) ->
            if (!countedFailure) now else retryAt(row.createdElapsedRealtimeMs, failures, now)
        } ?: now
        for ((row, failures) in survivors) {
            dao.requeue(
                id = row.id,
                batchId = if (contentChanged) null else row.batchId,
                failedAttempts = failures,
                nextAttemptAt = commonNextAt,
            )
        }
    }

    private fun retryAt(createdElapsedRealtimeMs: Long, failureCount: Int, now: Long): Long {
        val offset = RETRY_OFFSETS_MS[(failureCount - 1).coerceIn(0, RETRY_OFFSETS_MS.lastIndex)]
        val jitter = 0.8 + randomUnit().coerceIn(0.0, 1.0) * 0.4
        return maxOf(now, createdElapsedRealtimeMs + (offset * jitter).toLong())
    }

    /**
     * Drops every row left over from an earlier session — a previous device boot or a previous
     * process of this app — without uploading it. The queue is deliberately not carried across a
     * restart: the scan log and the scan/report counters live in memory and reset with the
     * process, so uploading rows this process never scanned would report more observations than it
     * scanned. Rows enqueued by *this* process carry the current anchor and are never touched.
     */
    private suspend fun discardRowsFromPreviousSessions() {
        if (bootChecked) return
        val stale = dao.fetchFromOtherBoots(bootAnchor())
        if (stale.isNotEmpty()) {
            dao.deleteByIds(stale.map { it.id })
            expired.addAndGet(stale.size)
            Log.i(TAG, "Discarded ${stale.size} queued observations from a previous session")
        }
        bootChecked = true
    }

    private suspend fun recoverInterruptedRequests(now: Long) {
        val interrupted = dao.fetchAllInFlight()
        if (interrupted.isEmpty()) return
        // Preserve the exact batch id after a crash so a response lost with the process is
        // retried idempotently. releaseBatch still drops an old row when that source has a
        // newer pending_latest; any partial content change clears the batch id for survivors.
        releaseBatch(interrupted, countedFailure = false, preserveBatch = true, now = now)
    }

    private suspend fun removeExpired(now: Long) {
        val rows = dao.fetchExpired(now)
        if (rows.isEmpty()) return
        dao.deleteByIds(rows.map { it.id })
        val affectedBatches = rows.mapNotNull { it.batchId }.distinct()
        if (affectedBatches.isNotEmpty()) dao.clearPendingBatchIds(affectedBatches)
        expired.addAndGet(rows.size)
    }

    private fun scheduleGlobalBackoff(now: Long, minimumMs: Long) {
        val delayMs = maxOf(minimumMs, globalBackoffMs)
        retryAfterMs.updateAndGet { maxOf(it, now + delayMs) }
        globalBackoffMs = minOf(globalBackoffMs * 2, 60L * 60 * 1_000)
    }

    private fun isLocallyValid(
        eidHex: String,
        payloadBase64: String,
        latitude: Double?,
        longitude: Double?,
        locationAccuracyMeters: Double?,
        locationSource: String,
        locationIsMock: Boolean,
        clientSeenAt: String,
    ): Boolean = runCatching {
        require(Regex("^[0-9a-fA-F]{16}$").matches(eidHex))
        require(Base64.getDecoder().decode(payloadBase64).size == 23)
        require((latitude == null) == (longitude == null))
        if (latitude != null && longitude != null) {
            require(latitude.isFinite() && longitude.isFinite())
            require(latitude in -90.0..90.0)
            require(longitude in -180.0..180.0)
            require(locationAccuracyMeters != null && locationAccuracyMeters.isFinite())
            require(locationAccuracyMeters >= 0.0)
            require(locationSource == "sdk_fused" || locationSource == "host_supplied")
        } else {
            require(locationAccuracyMeters == null)
            require(locationSource == "unknown")
            require(!locationIsMock)
        }
        Instant.parse(clientSeenAt)
    }.isSuccess

    private fun applyConfigFromErrorBody(response: Response<*>) {
        try {
            val body = response.errorBody()?.string() ?: return
            @Suppress("UNCHECKED_CAST")
            val map = com.google.gson.Gson().fromJson(body, Map::class.java) as? Map<String, Any?> ?: return
            @Suppress("UNCHECKED_CAST")
            (map["config"] as? Map<String, Any?>)?.let { configManager.applyServerConfig(it) }
        } catch (_: Exception) {
            // A malformed optional config must not bypass Retry-After.
        }
    }

    /**
     * Keeps [lastAcceptedBySource] bounded. Entries past the throttle interval can no longer gate
     * anything, so they go first; a still-live overflow is trimmed oldest-first.
     */
    private fun pruneAcceptedSources(now: Long) {
        if (lastAcceptedBySource.size <= MAX_ACCEPTED_SOURCES) return
        val intervalMs = configManager.sourceMinIntervalMs
        lastAcceptedBySource.entries.removeIf { now - it.value >= intervalMs }
        val overflow = lastAcceptedBySource.size - MAX_ACCEPTED_SOURCES
        if (overflow > 0) {
            lastAcceptedBySource.entries.sortedBy { it.value }.take(overflow).forEach { entry ->
                lastAcceptedBySource.remove(entry.key, entry.value)
            }
        }
    }

    private suspend fun updateStats() {
        val total = todayScan.get()
        val accepted = todayAccepted.get()
        _stats.value = ReportStats(
            todayScanCount = total,
            todayReportCount = accepted,
            pendingCount = dao.count(),
            failedCount = failed.get(),
            expiredCount = expired.get(),
            invalidCount = invalid.get(),
            queueFullCount = queueFull.get(),
            throttledCount = throttled.get(),
            // Throttled sightings are deliberately withheld, never attempted, so they stay out of
            // both the numerator and the denominator — counting them would depress the rate.
            successRate = if (total > 0) accepted.toFloat() / total else 0f,
            rateLimited = elapsedClockMs() < retryAfterMs.get(),
        )
    }
}
