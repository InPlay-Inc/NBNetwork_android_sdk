package com.nanobeaconnetwork

class NbnConfig private constructor(
    val serverUrl: String,
    val scanSource: ScanSource,
    val scanMode: ScanMode,
    val enableBackgroundScan: Boolean,
    val restartOnBoot: Boolean,
    val logLevel: LogLevel,
) {
    /**
     * Who performs the BLE scan.
     * - SDK_SCAN (default): the SDK owns a BluetoothLeScanner + foreground service and scans
     *   itself. Call [NbnClient.startScan] / [NbnClient.stopScan] to control it. This is independent
     *   of any scanning the host app does on its own.
     * - HOST_SCAN: the SDK never touches BluetoothLeScanner. The host app scans with its own
     *   scanner (must include the 0xFC32 filter) and feeds results via [NbnClient.submitScanResult]
     *   / [NbnClient.submitServiceData]. startScan()/stopScan() are no-ops; the SDK starts no
     *   foreground service.
     */
    enum class ScanSource { SDK_SCAN, HOST_SCAN }
    enum class ScanMode { LOW_POWER, BALANCED, LOW_LATENCY }
    enum class LogLevel { NONE, ERROR, WARN, INFO, DEBUG }

    class Builder {
        private var serverUrl: String = BuildConfig.DEFAULT_SERVER_URL
        private var scanSource: ScanSource = ScanSource.SDK_SCAN
        private var scanMode: ScanMode = ScanMode.BALANCED
        private var enableBackgroundScan: Boolean = true
        private var restartOnBoot: Boolean = true
        private var logLevel: LogLevel = LogLevel.WARN

        fun serverUrl(url: String) = apply { this.serverUrl = url }
        fun scanSource(source: ScanSource) = apply { this.scanSource = source }
        fun scanMode(mode: ScanMode) = apply { this.scanMode = mode }
        fun enableBackgroundScan(enable: Boolean) = apply { this.enableBackgroundScan = enable }

        /**
         * SDK_SCAN only: when true (default), the SDK re-starts its scan foreground service
         * after a device reboot if scanning was active when the device shut down. No effect in
         * HOST_SCAN mode.
         */
        fun restartOnBoot(enable: Boolean) = apply { this.restartOnBoot = enable }
        fun logLevel(level: LogLevel) = apply { this.logLevel = level }

        fun build() = NbnConfig(serverUrl, scanSource, scanMode, enableBackgroundScan, restartOnBoot, logLevel)
    }
}
