package com.nanobeaconnetwork.internal.time

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.util.UUID

/**
 * Supplies the monotonic clock and boot identity used by the bounded report queue.
 *
 * If BOOT_COUNT cannot be read, the process-scoped fallback intentionally causes queued
 * rows to be discarded after a process restart. An unverifiable age must never be
 * reconstructed from wall time.
 */
internal class BootSessionClock(private val context: Context) {
    private val processFallback = UUID.randomUUID().toString()

    fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    fun anchor(): String = runCatching {
        val count = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        require(count >= 0)
        "boot:$count"
    }.getOrElse { "process:$processFallback" }
}
