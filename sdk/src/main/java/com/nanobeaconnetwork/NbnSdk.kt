package com.nanobeaconnetwork

import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.Location
import android.os.Build
import android.util.Log
import com.nanobeaconnetwork.auth.AnonymousTokenManager
import com.nanobeaconnetwork.ble.AdvParser
import com.nanobeaconnetwork.ble.Deduplicator
import com.nanobeaconnetwork.internal.api.ApiClient
import com.nanobeaconnetwork.internal.config.ServerConfigManager
import com.nanobeaconnetwork.internal.db.SdkDatabase
import com.nanobeaconnetwork.internal.prefs.SdkPrefs
import com.nanobeaconnetwork.internal.service.BleScanService
import com.nanobeaconnetwork.location.LocationHelper
import com.nanobeaconnetwork.model.ReportStats
import com.nanobeaconnetwork.model.ScanEvent
import com.nanobeaconnetwork.model.ScanLogEntry
import com.nanobeaconnetwork.model.ScanState
import com.nanobeaconnetwork.report.ReportManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object NbnSdk {
    private var _context: Context? = null
    private val context get() = _context ?: error("NbnSdk not initialized. Call init() first.")

    private lateinit var prefs: SdkPrefs
    private lateinit var configManager: ServerConfigManager
    private lateinit var apiClient: ApiClient
    private lateinit var anonymousTokenManager: AnonymousTokenManager
    private lateinit var deduplicator: Deduplicator
    private lateinit var locationHelper: LocationHelper
    private lateinit var reportManager: ReportManager

    private val sdkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private const val TAG = "NbnSdk"

    // Default EXTERNAL: the host app owns scanning and feeds results via submitScanResult().
    private var scanSource: NbnConfig.ScanSource = NbnConfig.ScanSource.EXTERNAL

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _scanEvents = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 64)
    val scanEvents: SharedFlow<ScanEvent> = _scanEvents.asSharedFlow()

    val reportStats: StateFlow<ReportStats> get() = reportManager.stats

    private val _scanLogs = MutableStateFlow<List<ScanLogEntry>>(emptyList())
    val scanLogs: StateFlow<List<ScanLogEntry>> = _scanLogs.asStateFlow()

    private val logTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun init(context: Context, config: NbnConfig) {
        _context = context.applicationContext
        scanSource = config.scanSource
        prefs = SdkPrefs(context.applicationContext)
        if (config.serverUrl.isNotEmpty()) prefs.serverUrl = config.serverUrl

        configManager = ServerConfigManager(prefs)

        // Holder breaks the circular dependency: ApiClient needs the anonymous-token callback,
        // but AnonymousTokenManager needs ApiClient.
        val anonymousHolder = object {
            var fn: (suspend () -> String?)? = null
        }

        apiClient = ApiClient(
            prefs,
            { anonymousHolder.fn?.invoke() },
            debug = config.logLevel == NbnConfig.LogLevel.DEBUG,
        )

        anonymousTokenManager = AnonymousTokenManager(context.applicationContext, prefs, apiClient)
        anonymousHolder.fn = { anonymousTokenManager.ensureAnonymousToken().getOrNull() }

        val db = SdkDatabase.getInstance(context.applicationContext, prefs.databasePassphrase)
        reportManager = ReportManager(db.pendingReportDao(), apiClient, configManager, sdkScope)
        reportManager.start()

        // Reconcile scan-log entries with what the server actually acknowledged: promote the
        // batch's "Queued" entries to "Reported". The server response is aggregate-only
        // ({status,count}) with no per-item verdict, so when some records were dropped we
        // can't tell which — the dropped total is surfaced via reportStats.droppedCount.
        sdkScope.launch {
            reportManager.flushResults.collect { result ->
                markReported(result.eidPrefixes)
            }
        }

        deduplicator = Deduplicator(configManager)
        locationHelper = LocationHelper(context.applicationContext)

        createNotificationChannel(context.applicationContext)

        // Fetch anonymous token on first launch so report uploads work without login.
        sdkScope.launch { anonymousTokenManager.ensureAnonymousToken() }

        // Actively pull the latest server config on every launch (works logged-in or not),
        // so admin config changes take effect after an app restart. Failure is non-fatal.
        sdkScope.launch {
            runCatching { apiClient.service.getConfig().config?.let { configManager.applyServerConfig(it) } }
        }
    }

    /**
     * Start scanning. Only meaningful in SDK_MANAGED mode (starts the foreground scan service).
     * In the default EXTERNAL mode this is a no-op — the host app owns scanning and must feed
     * results via [submitScanResult] / [submitServiceData].
     */
    fun startScan() {
        if (scanSource == NbnConfig.ScanSource.EXTERNAL) {
            Log.w(TAG, "startScan() ignored: scanSource=EXTERNAL. Feed results via submitScanResult().")
            return
        }
        BleScanService.start(context)
    }

    /** Stop scanning (SDK_MANAGED only; no-op in EXTERNAL mode). */
    fun stopScan() {
        if (scanSource == NbnConfig.ScanSource.EXTERNAL) return
        BleScanService.stop(context)
    }

    /**
     * EXTERNAL mode: feed a raw scan result from the host app's own scanner. The SDK extracts
     * the 0xFC32 service data; results without it are ignored. No-op in SDK_MANAGED mode.
     */
    fun submitScanResult(result: ScanResult) {
        if (scanSource != NbnConfig.ScanSource.EXTERNAL) return
        val serviceData = result.scanRecord?.getServiceData(BleScanService.SERVICE_UUID) ?: return
        submitServiceData(serviceData, result.rssi, null, result.device?.address)
    }

    /**
     * EXTERNAL mode: feed the 0xFC32 service-data block directly (framework-agnostic). [location]
     * may be null, in which case the SDK fetches the current location itself. No-op in
     * SDK_MANAGED mode.
     */
    fun submitServiceData(serviceData: ByteArray, rssi: Int, location: Location? = null, bleAddress: String? = null) {
        if (scanSource != NbnConfig.ScanSource.EXTERNAL) return
        val advData = AdvParser.parse(serviceData) ?: return
        val eidHex = advData.eid.joinToString("") { "%02x".format(it) }
        internalOnScanResult(eidHex, advData.payload, rssi, isoFormat.format(Date()), bleAddress, location)
    }

    fun updateConfig(config: NbnConfig) {
        if (config.serverUrl.isNotEmpty()) {
            prefs.serverUrl = config.serverUrl
            apiClient.invalidate()
        }
    }

    fun shutdown() {
        stopScan()
        sdkScope.cancel()
        reportManager.stop()
    }

    /**
     * Central scan-result pipeline, shared by SDK_MANAGED (BleScanService) and EXTERNAL
     * (submitScanResult/submitServiceData): dedup -> location -> enqueue -> emit.
     * [explicitLocation] overrides the SDK's own location lookup when the caller already has one.
     */
    internal fun internalOnScanResult(
        eidHex: String, payloadHex: String, rssi: Int, timestamp: String,
        bleAddress: String? = null,
        explicitLocation: Location? = null,
    ) {
        sdkScope.launch {
            // Dedup per physical device: prefer the (static) BLE MAC address, which is stable
            // across EID rotation; fall back to the EID when no address is available.
            val dedupKey = bleAddress?.takeIf { it.isNotBlank() } ?: eidHex
            val isDuplicate = deduplicator.isDuplicate(dedupKey)
            val event = ScanEvent(eidHex = eidHex, rssi = rssi,
                timestamp = System.currentTimeMillis(), reported = !isDuplicate)
            _scanEvents.emit(event)

            val status: String
            if (!isDuplicate) {
                val location = explicitLocation ?: locationHelper.getLocation()
                val lat = location?.latitude ?: 0.0
                val lon = location?.longitude ?: 0.0
                reportManager.enqueue(eidHex, payloadHex, rssi, lat, lon, timestamp)
                // Only enqueued locally so far — not yet confirmed by the server. It flips
                // to "Reported" once ReportManager reports a successful flush (see init()).
                status = "Queued"
            } else {
                status = "Duplicate"
            }

            addScanLog(eidHex.take(8), rssi, status)
        }
    }

    internal fun internalUpdateScanState(
        isScanning: Boolean? = null,
        bleEnabled: Boolean? = null,
        gpsEnabled: Boolean? = null,
    ) {
        _scanState.value = _scanState.value.copy(
            isScanning = isScanning ?: _scanState.value.isScanning,
            bleEnabled = bleEnabled ?: _scanState.value.bleEnabled,
            gpsEnabled = gpsEnabled ?: _scanState.value.gpsEnabled,
        )
    }

    private fun addScanLog(eidPrefix: String, rssi: Int, status: String) {
        val entry = ScanLogEntry(
            time = logTimeFmt.format(Date()),
            eidPrefix = eidPrefix,
            rssi = rssi,
            status = status,
        )
        val current = _scanLogs.value.toMutableList()
        current.add(0, entry)
        if (current.size > 100) current.removeAt(current.size - 1)
        _scanLogs.value = current
    }

    // Flip "Queued" scan-log entries whose EID prefix was in a server-acknowledged batch to
    // "Reported". Prefixes may repeat, so update every matching queued entry.
    private fun markReported(eidPrefixes: List<String>) {
        if (eidPrefixes.isEmpty()) return
        val prefixes = eidPrefixes.toHashSet()
        val current = _scanLogs.value
        if (current.none { it.status == "Queued" && it.eidPrefix in prefixes }) return
        _scanLogs.value = current.map {
            if (it.status == "Queued" && it.eidPrefix in prefixes) it.copy(status = "Reported") else it
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BleScanService.CHANNEL_ID,
                "NanoBeaconNetwork Scanning",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "BLE scanning service" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun String.hexToBytes(): ByteArray {
        val len = length / 2
        return ByteArray(len) { i -> this.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
