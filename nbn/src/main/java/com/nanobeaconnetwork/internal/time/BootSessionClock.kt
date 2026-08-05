package com.nanobeaconnetwork.internal.time

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.util.UUID

/**
 * Supplies the monotonic clock and session identity used by the bounded report queue.
 *
 * The anchor is **process-scoped**: it pairs the boot counter with a per-process UUID, so it
 * changes both across a device reboot and across a plain app restart. Rows carrying any other
 * anchor are discarded on the next flush rather than uploaded:
 *
 * - Across a reboot their age is unverifiable — `elapsedRealtime` is only comparable within one
 *   boot, and an unverifiable age must never be reconstructed from wall time.
 * - Across a process restart the age is still verifiable, but the queue is deliberately not
 *   carried over: the in-memory scan log and counters reset with the process, so uploading rows
 *   the new process never scanned would report more observations than it scanned.
 *
 * The [processFallback] UUID alone is used when BOOT_COUNT cannot be read, which keeps the
 * discard-on-restart behaviour even without the boot counter.
 */
internal class BootSessionClock(private val context: Context) {
    private val processFallback = UUID.randomUUID().toString()

    fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    fun anchor(): String = runCatching {
        val count = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        require(count >= 0)
        "boot:$count|proc:$processFallback"
    }.getOrElse { "process:$processFallback" }
}
