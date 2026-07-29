package com.nanobeaconnetwork.ble

import com.nanobeaconnetwork.internal.config.ServerConfigManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side de-duplication of scanned beacons.
 *
 * The key is the beacon's **BLE MAC address** (the physical-device identity, stable across EID
 * rotation); callers fall back to the EID hex only when no address is available (the HOST_SCAN
 * service-data path). Within [ServerConfigManager.dedupWindowMs] a key is reported at most once.
 *
 * The last-seen timestamp is stamped at scan time and is deliberately **not** tied to whether the
 * server later stored or dropped the report — so a server drop never re-opens the window; the next
 * send for that address happens only after the window elapses.
 */
internal class Deduplicator(
    private val configManager: ServerConfigManager,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val seen = ConcurrentHashMap<String, Long>()

    /** Returns true if this key was seen recently within the configured dedup window. */
    fun isDuplicate(key: String): Boolean {
        val now = clockMs()
        val windowMs = configManager.dedupWindowMs

        var duplicate = false
        seen.compute(key) { _, lastSeen ->
            if (lastSeen != null && now - lastSeen < windowMs) {
                duplicate = true
                lastSeen
            } else {
                now
            }
        }

        if (seen.size > MAX_CACHE_SIZE) {
            seen.entries.removeIf { now - it.value >= windowMs }
            val overflow = seen.size - MAX_CACHE_SIZE
            if (overflow > 0) {
                seen.entries.sortedBy { it.value }.take(overflow).forEach { entry ->
                    seen.remove(entry.key, entry.value)
                }
            }
        }
        return duplicate
    }

    fun clear() = seen.clear()

    private companion object {
        const val MAX_CACHE_SIZE = 1_000
    }
}
