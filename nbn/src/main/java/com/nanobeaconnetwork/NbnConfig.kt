package com.nanobeaconnetwork

import java.net.URI

class NbnConfig private constructor(
    // null = use the production endpoint (BuildConfig.DEFAULT_SERVER_URL). Only ever honored in a
    // debuggable build; see NbnClient.resolveServerUrl.
    internal val debugServerUrl: String?,
    val scanSource: ScanSource,
    val scanMode: ScanMode,
    val restartOnBoot: Boolean,
    val logLevel: LogLevel,
) {
    /**
     * Who performs the BLE scan.
     * - LIBRARY_SCAN (default): the library owns a BluetoothLeScanner + foreground service and scans
     *   itself. Call [NbnClient.startScan] / [NbnClient.stopScan] to control it. This is independent
     *   of any scanning the host app does on its own.
     * - HOST_SCAN: the library never touches BluetoothLeScanner. The host app scans with its own
     *   scanner (must include the 0xFC32 filter) and feeds results via [NbnClient.submitScanResult]
     *   / [NbnClient.submitServiceData]. startScan()/stopScan() are no-ops; the library starts no
     *   foreground service.
     */
    enum class ScanSource { LIBRARY_SCAN, HOST_SCAN }
    enum class ScanMode { LOW_POWER, BALANCED, LOW_LATENCY }
    enum class LogLevel { NONE, ERROR, WARN, INFO, DEBUG }

    class Builder {
        private var debugServerUrl: String? = null
        private var scanSource: ScanSource = ScanSource.LIBRARY_SCAN
        private var scanMode: ScanMode = ScanMode.LOW_POWER
        private var restartOnBoot: Boolean = DEFAULT_RESTART_ON_BOOT
        private var logLevel: LogLevel = LogLevel.WARN

        /**
         * **Debugging only.** Points the library at a non-production server (e.g. a local or staging
         * deployment). Leave this unset in shipping apps: the reporting endpoint is not a host
         * configuration knob, and the value is **ignored unless the app is debuggable** — a release
         * build always talks to the production endpoint and logs a warning if this was set.
         */
        fun debugServerUrl(url: String) = apply { this.debugServerUrl = url }

        fun scanSource(source: ScanSource) = apply { this.scanSource = source }
        fun scanMode(mode: ScanMode) = apply { this.scanMode = mode }

        /**
         * LIBRARY_SCAN only: whether to re-start the scan foreground service after a device reboot, if
         * scanning was active when the device shut down. **Enabled by default**, but it can only
         * take effect when the host app declares and obtains `ACCESS_BACKGROUND_LOCATION` — a
         * foreground service started from the background cannot access location without it, so the
         * permission is effectively the switch. Pass `false` to opt out even when that permission is
         * held. No effect in HOST_SCAN mode.
         */
        fun restartOnBoot(enable: Boolean) = apply { this.restartOnBoot = enable }
        fun logLevel(level: LogLevel) = apply { this.logLevel = level }

        fun build(): NbnConfig {
            // Validate only when a debug override was actually supplied; null means "production".
            val normalizedUrl = debugServerUrl?.let { normalizeAndValidateServerUrl(it) }
            return NbnConfig(
                normalizedUrl,
                scanSource,
                scanMode,
                restartOnBoot,
                logLevel,
            )
        }
    }

    internal companion object {
        const val DEFAULT_RESTART_ON_BOOT = true

        /**
         * Normalizes (trim + strip a trailing '/') and validates a server URL, returning the
         * normalized value or throwing [IllegalArgumentException]. Shared by [Builder.build] and
         * [NbnClient.setDebugServerUrl] so both enforce the same rule.
         */
        internal fun normalizeAndValidateServerUrl(raw: String): String {
            val normalized = raw.trim().trimEnd('/')
            require(normalized.isNotEmpty()) { "Server URL must not be empty" }

            val uri = runCatching { URI(normalized) }.getOrNull()
            require(
                uri != null &&
                    (uri.scheme == "https" || uri.scheme == "http") &&
                    !uri.host.isNullOrBlank() &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null
            ) { "Server URL must be an absolute HTTP(S) URL without a query or fragment" }

            return normalized
        }
    }
}
