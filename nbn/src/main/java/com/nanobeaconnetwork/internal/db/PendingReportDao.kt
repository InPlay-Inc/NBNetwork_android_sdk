package com.nanobeaconnetwork.internal.db

import androidx.room.*

@Dao
internal interface PendingReportDao {

    // UPSERT by eidHex unique index (design §5.7): a repeat EID replaces the queued row
    // so the latest GPS/rssi wins. Row count stays unchanged on conflict.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: PendingReport): Long

    @Query("SELECT * FROM pending_reports ORDER BY createdAt ASC LIMIT :limit")
    suspend fun fetchBatch(limit: Int): List<PendingReport>

    @Query("DELETE FROM pending_reports WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE pending_reports SET retryCount = retryCount + 1 WHERE id IN (:ids)")
    suspend fun incrementRetries(ids: List<Long>)

    @Query("DELETE FROM pending_reports WHERE retryCount >= :maxRetries OR createdAt < :expiryMs")
    suspend fun deleteExpiredAndExhausted(maxRetries: Int, expiryMs: Long): Int

    @Query("SELECT COUNT(*) FROM pending_reports")
    suspend fun count(): Int

    @Query("DELETE FROM pending_reports WHERE id IN (SELECT id FROM pending_reports ORDER BY createdAt ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)
}
