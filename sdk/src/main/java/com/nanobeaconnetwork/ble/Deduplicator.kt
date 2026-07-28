package com.nanobeaconnetwork.ble

import com.nanobeaconnetwork.internal.config.ServerConfigManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side de-duplication of scanned beacons.
 *
 * The key is the beacon's **BLE MAC address** (the physical-device identity, stable across EID
 * rotation); callers fall back to the EID hex only when no address is available (EXTERNAL
 * service-data path). Within [ServerConfigManager.dedupWindowMs] a key is reported at most once.
 *
 * The last-seen timestamp is stamped at scan time and is deliberately **not** tied to whether the
 * server later stored or dropped the report — so a server drop never re-opens the window; the next
 * send for that address happens only after the window elapses.
 */
internal class Deduplicator(private val configManager: ServerConfigManager) {
    private val seen = ConcurrentHashMap<String, Long>()

    /** Returns true if this key was seen recently within the configured dedup window. */
    fun isDuplicate(key: String): Boolean {
        val now = System.currentTimeMillis()
        val windowMs = configManager.dedupWindowMs
        val lastSeen = seen[key]
        return if (lastSeen != null && now - lastSeen < windowMs) {
            true
        } else {
            seen[key] = now
            if (seen.size > 1000) {
                seen.entries.removeIf { now - it.value > windowMs }
            }
            false
        }
    }

    fun clear() = seen.clear()
}
