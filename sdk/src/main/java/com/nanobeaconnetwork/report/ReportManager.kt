package com.nanobeaconnetwork.report

import android.util.Log
import com.nanobeaconnetwork.internal.api.ApiClient
import com.nanobeaconnetwork.internal.api.BatchReportRequest
import com.nanobeaconnetwork.internal.api.ReportItem
import com.nanobeaconnetwork.internal.config.ServerConfigManager
import com.nanobeaconnetwork.internal.db.PendingReport
import com.nanobeaconnetwork.internal.db.PendingReportDao
import com.nanobeaconnetwork.model.ReportStats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Result of one batch flush that the server acknowledged (HTTP 2xx). [accepted] is what
 * the server stored or deduped; [dropped] is what it silently discarded (unknown/forged
 * EID). [eidPrefixes] are the 8-char prefixes of the records in the batch, so the UI can
 * flip their scan-log entries from "Queued" to a server-confirmed state.
 */
internal data class FlushResult(
    val eidPrefixes: List<String>,
    val accepted: Int,
    val dropped: Int,
)

private const val TAG = "ReportManager"
private const val MAX_QUEUE_SIZE = 10_000
private const val MAX_RETRIES = 10
private const val BATCH_SIZE = 500
private const val EXPIRE_DAYS_MS = 7L * 24 * 60 * 60 * 1000

internal class ReportManager(
    private val dao: PendingReportDao,
    private val apiClient: ApiClient,
    private val configManager: ServerConfigManager,
    private val scope: CoroutineScope,
) {
    private val _stats = MutableStateFlow(ReportStats())
    val stats: StateFlow<ReportStats> = _stats.asStateFlow()

    // Emitted after each server-acknowledged flush so the UI can reconcile scan-log entries.
    private val _flushResults = MutableSharedFlow<FlushResult>(extraBufferCapacity = 64)
    val flushResults: SharedFlow<FlushResult> = _flushResults.asSharedFlow()

    private var retryAfterMs = AtomicLong(0)
    private var backoffMs = 1000L
    private var lastFlushMs = 0L
    private var todayScan = 0
    private var todayReport = 0
    private var todayDropped = 0

    private var flushJob: Job? = null

    fun start() {
        flushJob = scope.launch {
            while (isActive) {
                delay(1000L)
                tryFlush()
            }
        }
    }

    fun stop() {
        flushJob?.cancel()
    }

    suspend fun enqueue(
        eidHex: String,
        payloadHex: String,
        rssi: Int,
        latitude: Double,
        longitude: Double,
        timestamp: String,
    ) {
        todayScan++
        val count = dao.count()
        if (count >= MAX_QUEUE_SIZE) {
            dao.deleteOldest(count - MAX_QUEUE_SIZE + 1)
        }
        dao.upsert(
            PendingReport(
                eidHex = eidHex,
                payloadHex = payloadHex,
                rssi = rssi,
                latitude = latitude,
                longitude = longitude,
                timestamp = timestamp,
            )
        )
        updateStats()

        if (dao.count() >= configManager.batchThreshold) {
            scope.launch { tryFlush() }
        }
    }

    internal suspend fun tryFlush() {
        val now = System.currentTimeMillis()
        if (now < retryAfterMs.get()) return
        if (now - lastFlushMs < configManager.reportMinIntervalMs) return

        val pending = dao.fetchBatch(BATCH_SIZE)
        if (pending.isEmpty()) return

        val items = pending.map {
            ReportItem(payload = it.payloadHex, rssi = it.rssi,
                latitude = it.latitude, longitude = it.longitude, timestamp = it.timestamp)
        }
        val ids = pending.map { it.id }

        try {
            val resp = apiClient.service.batchReport(BatchReportRequest(items))
            // A 2xx response means the whole batch was processed server-side (stored,
            // deduped, or dropped for unknown/forged EIDs). None of these are retryable,
            // so clear the batch. The server response carries {status, count} only, where
            // `count` is what it accepted (stored + deduped); the remainder was silently
            // dropped (unknown/forged EID). Reflect both truthfully instead of assuming
            // every 2xx record was stored.
            val accepted = resp.count.coerceIn(0, ids.size)
            val dropped = ids.size - accepted
            dao.deleteByIds(ids)
            todayReport += accepted
            todayDropped += dropped
            lastFlushMs = now
            backoffMs = 1000L
            _flushResults.tryEmit(
                FlushResult(pending.map { it.eidHex.take(8) }, accepted, dropped)
            )
            if (dropped > 0) {
                Log.w(TAG, "Server dropped $dropped/${ids.size} records (accepted=$accepted). " +
                    "Uploads reach the server but these EIDs aren't resolvable there " +
                    "(unregistered / not precomputed / key mismatch).")
            } else {
                Log.d(TAG, "Reported ${ids.size} records (server accepted=$accepted)")
            }
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 429) {
                val retryAfter = e.response()?.headers()?.get("Retry-After")?.toLongOrNull() ?: 60
                retryAfterMs.set(now + retryAfter * 1000)
                _stats.value = _stats.value.copy(rateLimited = true)
                applyConfigFromErrorBody(e) // 429 body carries a stricter config to slow down
                Log.w(TAG, "Rate limited, retry after ${retryAfter}s")
            } else {
                dao.incrementRetries(ids)
                scheduleBackoff()
                Log.w(TAG, "HTTP ${e.code()}: ${e.message()}")
            }
        } catch (e: Exception) {
            dao.incrementRetries(ids)
            scheduleBackoff()
            Log.w(TAG, "Report failed: ${e.message}")
        }

        dao.deleteExpiredAndExhausted(MAX_RETRIES, now - EXPIRE_DAYS_MS)
        updateStats()
    }

    private fun scheduleBackoff() {
        retryAfterMs.set(System.currentTimeMillis() + backoffMs)
        backoffMs = minOf(backoffMs * 2, 60_000L)
    }

    // applyConfigFromErrorBody parses the `config` object from a 429 error body and
    // applies it (design §5.3: server sends a stricter config to slow the client down).
    private fun applyConfigFromErrorBody(e: retrofit2.HttpException) {
        try {
            val body = e.response()?.errorBody()?.string() ?: return
            @Suppress("UNCHECKED_CAST")
            val map = com.google.gson.Gson().fromJson(body, Map::class.java) as? Map<String, Any?> ?: return
            @Suppress("UNCHECKED_CAST")
            (map["config"] as? Map<String, Any?>)?.let { configManager.applyServerConfig(it) }
        } catch (_: Exception) {
            // ignore malformed error bodies
        }
    }

    private suspend fun updateStats() {
        val pending = dao.count()
        val rateLimited = System.currentTimeMillis() < retryAfterMs.get()
        val total = todayScan
        val rate = if (total > 0) todayReport.toFloat() / total else 0f
        _stats.value = ReportStats(
            todayScanCount = todayScan,
            todayReportCount = todayReport,
            pendingCount = pending,
            failedCount = 0,
            droppedCount = todayDropped,
            successRate = rate,
            rateLimited = rateLimited,
        )
    }

    fun onScanCount() { todayScan++ }
}
