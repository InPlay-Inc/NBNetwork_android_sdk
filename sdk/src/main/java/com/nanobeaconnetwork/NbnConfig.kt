package com.nanobeaconnetwork

class NbnConfig private constructor(
    val serverUrl: String,
    val scanSource: ScanSource,
    val scanMode: ScanMode,
    val enableBackgroundScan: Boolean,
    val logLevel: LogLevel,
) {
    /**
     * Where BLE advertisements come from.
     * - EXTERNAL (default): the SDK never touches BluetoothLeScanner. The host app scans with
     *   its own scanner (must include the 0xFC32 filter) and feeds results via
     *   [NbnSdk.submitScanResult] / [NbnSdk.submitServiceData]. startScan()/stopScan() are
     *   no-ops; the SDK requests no BLE permission and starts no foreground service.
     * - SDK_MANAGED: the SDK owns a BluetoothLeScanner + foreground service and scans itself.
     */
    enum class ScanSource { SDK_MANAGED, EXTERNAL }
    enum class ScanMode { LOW_POWER, BALANCED, LOW_LATENCY }
    enum class LogLevel { NONE, ERROR, WARN, INFO, DEBUG }

    class Builder {
        private var serverUrl: String = BuildConfig.DEFAULT_SERVER_URL
        private var scanSource: ScanSource = ScanSource.EXTERNAL
        private var scanMode: ScanMode = ScanMode.BALANCED
        private var enableBackgroundScan: Boolean = true
        private var logLevel: LogLevel = LogLevel.WARN

        fun serverUrl(url: String) = apply { this.serverUrl = url }
        fun scanSource(source: ScanSource) = apply { this.scanSource = source }
        fun scanMode(mode: ScanMode) = apply { this.scanMode = mode }
        fun enableBackgroundScan(enable: Boolean) = apply { this.enableBackgroundScan = enable }
        fun logLevel(level: LogLevel) = apply { this.logLevel = level }

        fun build() = NbnConfig(serverUrl, scanSource, scanMode, enableBackgroundScan, logLevel)
    }
}
