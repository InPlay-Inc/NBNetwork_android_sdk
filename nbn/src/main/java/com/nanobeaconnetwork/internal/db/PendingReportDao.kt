package com.nanobeaconnetwork.internal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface PendingReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLatest(report: PendingReport): Long

    @Query("SELECT * FROM pending_reports WHERE sourceKey=:sourceKey AND slot='pending_latest' LIMIT 1")
    suspend fun findPending(sourceKey: String): PendingReport?

    @Query("SELECT COUNT(*) FROM pending_reports WHERE sourceKey=:sourceKey AND slot='pending_latest'")
    suspend fun hasPending(sourceKey: String): Int

    @Query("SELECT * FROM pending_reports WHERE slot='pending_latest' AND nextAttemptElapsedRealtimeMs<=:now ORDER BY createdElapsedRealtimeMs,id LIMIT :limit")
    suspend fun fetchReady(now: Long, limit: Int): List<PendingReport>

    @Query("SELECT * FROM pending_reports WHERE slot='pending_latest' AND batchId=:batchId AND nextAttemptElapsedRealtimeMs<=:now ORDER BY createdElapsedRealtimeMs,id LIMIT :limit")
    suspend fun fetchReadyBatch(batchId: String, now: Long, limit: Int): List<PendingReport>

    @Query("UPDATE pending_reports SET slot='in_flight',batchId=:batchId WHERE id IN (:ids) AND slot='pending_latest'")
    suspend fun markInFlight(ids: List<Long>, batchId: String): Int

    @Query("SELECT * FROM pending_reports WHERE slot='in_flight' AND batchId=:batchId ORDER BY createdElapsedRealtimeMs,id")
    suspend fun fetchInFlightBatch(batchId: String): List<PendingReport>

    @Query("SELECT * FROM pending_reports WHERE slot='in_flight' ORDER BY createdElapsedRealtimeMs,id")
    suspend fun fetchAllInFlight(): List<PendingReport>

    @Query("UPDATE pending_reports SET slot='pending_latest',batchId=:batchId,failedAttempts=:failedAttempts,nextAttemptElapsedRealtimeMs=:nextAttemptAt WHERE id=:id AND slot='in_flight'")
    suspend fun requeue(id: Long, batchId: String?, failedAttempts: Int, nextAttemptAt: Long): Int

    @Query("UPDATE pending_reports SET batchId=NULL WHERE slot='pending_latest' AND batchId IN (:batchIds)")
    suspend fun clearPendingBatchIds(batchIds: List<String>)

    @Query("SELECT * FROM pending_reports WHERE expiresElapsedRealtimeMs<=:now")
    suspend fun fetchExpired(now: Long): List<PendingReport>

    @Query("SELECT * FROM pending_reports WHERE bootAnchor<>:bootAnchor")
    suspend fun fetchFromOtherBoots(bootAnchor: String): List<PendingReport>

    @Query("DELETE FROM pending_reports WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("SELECT COUNT(*) FROM pending_reports")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(length(observationId)+length(sourceKey)+length(COALESCE(batchId,''))+length(eidHex)+length(payloadBase64)+length(locationSource)+length(clientSeenAt)+length(bootAnchor)+256),0) FROM pending_reports")
    suspend fun estimatedBytes(): Long
}
