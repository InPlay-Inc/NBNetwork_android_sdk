package com.nanobeaconnetwork

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.ApplicationInfo
import android.location.Location
import android.util.Log
import androidx.core.location.LocationCompat
import com.nanobeaconnetwork.auth.AnonymousTokenManager
import com.nanobeaconnetwork.ble.AdvParser
import com.nanobeaconnetwork.ble.Deduplicator
import com.nanobeaconnetwork.internal.api.ApiClient
import com.nanobeaconnetwork.internal.config.ServerConfigManager
import com.nanobeaconnetwork.internal.db.NbnDatabase
import com.nanobeaconnetwork.internal.prefs.NbnPrefs
import com.nanobeaconnetwork.internal.security.AndroidInstallationIdentity
import com.nanobeaconnetwork.internal.security.InstallationKeyException
import com.nanobeaconnetwork.internal.service.BleScanService
import com.nanobeaconnetwork.internal.time.BootSessionClock
import com.nanobeaconnetwork.location.LocationHelper
import com.nanobeaconnetwork.model.ReportStats
import com.nanobeaconnetwork.model.ScanEvent
import com.nanobeaconnetwork.model.ScanLogEntry
import com.nanobeaconnetwork.model.ScanState
import com.nanobeaconnetwork.report.ReportManager
import com.nanobeaconnetwork.report.EnqueueResult
import com.nanobeaconnetwork.report.SourceKeyFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// Safe by construction: the only Context this singleton — and the helpers it holds, e.g.
// AnonymousTokenManager/LocationHelper — ever stores is context.applicationContext (set in
// init()). The application context lives for the whole process, so nothing is leaked. Lint
// can't prove the assignment is the app context, so StaticFieldLeak is suppressed object-wide.
@SuppressLint("StaticFieldLeak")
object NbnClient {
    @Volatile private var _context: Context? = null
    private val context get() = _context ?: error("NbnClient not initialized. Call init() first.")

    private lateinit var prefs: NbnPrefs
    private lateinit var configManager: ServerConfigManager
    private lateinit var apiClient: ApiClient
    private lateinit var anonymousTokenManager: AnonymousTokenManager
    private lateinit var deduplicator: Deduplicator
    private lateinit var locationHelper: LocationHelper
    private lateinit var reportManager: ReportManager

    private lateinit var sdkScope: CoroutineScope

    private const val TAG = "NbnClient"

    private fun newSdkScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * The endpoint to actually use. A debug override is honored only in a debuggable build, so a
     * release app can never be shipped pointing at a test server.
     */
    private fun resolveServerUrl(context: Context, debugUrl: String?): String {
        if (debugUrl == null) return BuildConfig.DEFAULT_SERVER_URL
        if (isDebuggable(context)) return debugUrl
        Log.w(TAG, "debugServerUrl ignored in a non-debuggable build; using the production endpoint")
        return BuildConfig.DEFAULT_SERVER_URL
    }

    internal fun internalScanMode(): NbnConfig.ScanMode = prefs.scanMode

    // Default LIBRARY_SCAN: the library owns its own foreground scan service (start via startScan()).
    private var scanSource: NbnConfig.ScanSource = NbnConfig.ScanSource.LIBRARY_SCAN

    private val logTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _scanEvents = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 64)
    val scanEvents: SharedFlow<ScanEvent> = _scanEvents.asSharedFlow()

    val reportStats: StateFlow<ReportStats> get() = reportManager.stats

    private val _scanLogs = MutableStateFlow<List<ScanLogEntry>>(emptyList())
    val scanLogs: StateFlow<List<ScanLogEntry>> = _scanLogs.asStateFlow()


    /**
     * Initializes the library. Call once, typically from `Application.onCreate()`; needs no permissions.
     *
     * [config] may be omitted to accept every default (production server URL, `LIBRARY_SCAN`,
     * `restartOnBoot = true`, `LOW_POWER`, `LogLevel.WARN`) — i.e. `NbnClient.init(context)`.
     * Calling this again after a successful init is ignored (a warning is logged).
     */
    @JvmOverloads
    @Synchronized
    fun init(context: Context, config: NbnConfig = NbnConfig.Builder().build()) {
        if (_context != null) {
            Log.w(TAG, "init() ignored: NbnClient is already initialized")
            return
        }
        sdkScope = newSdkScope()
        scanSource = config.scanSource
        prefs = NbnPrefs(context.applicationContext)
        prefs.serverUrl = resolveServerUrl(context.applicationContext, config.debugServerUrl)
        prefs.restartOnBoot = config.restartOnBoot

        prefs.scanMode = config.scanMode
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

        val installationIdentity = AndroidInstallationIdentity()
        anonymousTokenManager = AnonymousTokenManager(installationIdentity, prefs, apiClient)
        anonymousHolder.fn = { anonymousTokenManager.ensureAnonymousToken().getOrNull() }

        val db = NbnDatabase.getInstance(context.applicationContext, prefs.databasePassphrase)
        val bootClock = BootSessionClock(context.applicationContext)
        reportManager = ReportManager(
            db.pendingReportDao(),
            apiClient,
            configManager,
            sdkScope,
            elapsedClockMs = bootClock::elapsedRealtimeMs,
            bootAnchor = bootClock::anchor,
            evidenceProvider = { request ->
                var bearer = anonymousTokenManager.ensureAnonymousToken().getOrThrow()
                try {
                    installationIdentity.signReport(request, bearer)
                } catch (_: InstallationKeyException) {
                    installationIdentity.rotate()
                    prefs.anonymousToken = ""
                    prefs.anonymousTokenInstallationKeyId = ""
                    bearer = anonymousTokenManager.ensureAnonymousToken().getOrThrow()
                    installationIdentity.signReport(request, bearer)
                }
            },
        )
        reportManager.start()

        // A matching 202 only means the batch entered the server's durable bounded chain.
        // It intentionally reveals no EID or tag-verification result.
        sdkScope.launch {
            reportManager.flushResults.collect { result ->
                markReported(result.eidPrefixes)
            }
        }

        deduplicator = Deduplicator(configManager)
        locationHelper = LocationHelper(context.applicationContext)

        createNotificationChannel(context.applicationContext)
        _context = context.applicationContext

        // Fetch anonymous token on first launch so report uploads work without login.
        sdkScope.launch { anonymousTokenManager.ensureAnonymousToken() }

        // Actively pull the latest server config on every launch (works logged-in or not),
        // so admin config changes take effect after an app restart. Failure is non-fatal.
        sdkScope.launch {
            runCatching { apiClient.service.getConfig().config?.let { configManager.applyServerConfig(it) } }
        }
    }

    /**
     * Start scanning. Only meaningful in LIBRARY_SCAN mode (starts the foreground scan service).
     * In HOST_SCAN mode this is a no-op — the host app owns scanning and must feed results via
     * [submitScanResult] / [submitServiceData].
     */
    fun startScan() {
        val appContext = context
        if (scanSource == NbnConfig.ScanSource.HOST_SCAN) {
            Log.w(TAG, "startScan() ignored: scanSource=HOST_SCAN. Feed results via submitScanResult().")
            return
        }
        val hasPermissions = NbnPermissions.checkScanPermissions(appContext)
        internalUpdateScanState(hasPermissions = hasPermissions)
        if (!hasPermissions) {
            Log.e(TAG, "startScan() ignored: required BLE/location permissions are missing")
            return
        }
        prefs.scanEnabled = true
        BleScanService.start(appContext)
    }

    /** Stop scanning (LIBRARY_SCAN only; no-op in HOST_SCAN mode). */
    fun stopScan() {
        val appContext = context
        if (scanSource == NbnConfig.ScanSource.HOST_SCAN) return
        prefs.scanEnabled = false
        BleScanService.stop(appContext)
    }

    /**
     * Internal: bring the library up from persisted state when it is started by the system (e.g.
     * BootReceiver after a reboot) before the host has called [init]. No-op if already
     * initialized. Rebuilds a minimal LIBRARY_SCAN config from prefs (persisted server URL).
     */
    @Synchronized
    internal fun ensureInitialized(context: Context) {
        if (_context != null) return
        val p = NbnPrefs(context.applicationContext)
        val config = NbnConfig.Builder()
            .apply {
                // Carry a persisted debug override across the restart, but don't re-declare one
                // when the stored URL is just the production endpoint (avoids a spurious warning
                // in release builds on every boot).
                val stored = p.serverUrl
                if (stored.isNotEmpty() && stored != BuildConfig.DEFAULT_SERVER_URL) {
                    debugServerUrl(stored)
                }
            }
            .scanMode(p.scanMode)
            .scanSource(NbnConfig.ScanSource.LIBRARY_SCAN)
            .build()
        init(context.applicationContext, config)
    }

    /**
     * HOST_SCAN mode: feed a raw scan result from the host app's own scanner. The library extracts
     * the 0xFC32 service data; results without it are ignored. No-op in LIBRARY_SCAN mode.
     */
    fun submitScanResult(result: ScanResult) {
        check(_context != null) { "NbnClient not initialized. Call init() first." }
        if (scanSource != NbnConfig.ScanSource.HOST_SCAN) return
        val serviceData = result.scanRecord?.getServiceData(BleScanService.SERVICE_UUID) ?: return
        submitServiceData(serviceData, result.rssi, null, result.device?.address)
    }

    /**
     * HOST_SCAN mode: feed the 0xFC32 service-data block directly (framework-agnostic). [location]
     * may be null, in which case the library fetches the current location itself. No-op in LIBRARY_SCAN
     * mode.
     */
    fun submitServiceData(serviceData: ByteArray, rssi: Int, location: Location? = null, bleAddress: String? = null) {
        check(_context != null) { "NbnClient not initialized. Call init() first." }
        if (scanSource != NbnConfig.ScanSource.HOST_SCAN) return
        val advData = AdvParser.parse(serviceData) ?: return
        val eidHex = advData.eid.joinToString("") { "%02x".format(it) }
        internalOnScanResult(eidHex, advData.payload, rssi, DateTimeFormatter.ISO_INSTANT.format(Instant.now()), bleAddress, location)
    }

    /**
     * **Debugging only.** Repoints the library at a non-production server at runtime; takes effect on
     * the next request. The reporting endpoint is not a host configuration knob, so this is
     * **ignored unless the app is debuggable** (a warning is logged instead). [url] is validated
     * the same way as [NbnConfig.Builder.debugServerUrl] and throws on a malformed value.
     */
    fun setDebugServerUrl(url: String) {
        check(_context != null) { "NbnClient not initialized. Call init() first." }
        val normalized = NbnConfig.normalizeAndValidateServerUrl(url)
        if (!isDebuggable(context)) {
            Log.w(TAG, "setDebugServerUrl() ignored: only honored in a debuggable build")
            return
        }
        prefs.serverUrl = normalized
        apiClient.invalidate()
    }

    /**
     * Changes the BLE scan mode. Persisted immediately; in LIBRARY_SCAN mode the new mode applies the
     * next time scanning starts, so call [stopScan] + [startScan] to apply it to an active scan.
     */
    fun setScanMode(mode: NbnConfig.ScanMode) {
        check(_context != null) { "NbnClient not initialized. Call init() first." }
        prefs.scanMode = mode
    }

    /**
     * Sets whether LIBRARY_SCAN scanning auto-resumes after a device reboot. Enabled by default, but it
     * only takes effect while the host holds `ACCESS_BACKGROUND_LOCATION` (see
     * [NbnConfig.Builder.restartOnBoot]); pass `false` to opt out.
     */
    fun setRestartOnBoot(enable: Boolean) {
        check(_context != null) { "NbnClient not initialized. Call init() first." }
        prefs.restartOnBoot = enable
    }

    @Synchronized
    fun shutdown() {
        val appContext = _context ?: return
        if (scanSource == NbnConfig.ScanSource.LIBRARY_SCAN) {
            prefs.scanEnabled = false
            BleScanService.stop(appContext)
        }
        reportManager.stop()
        sdkScope.cancel()
        deduplicator.clear()
        _scanState.value = ScanState()
        _scanLogs.value = emptyList()
        _context = null
    }

    /**
     * Central scan-result pipeline, shared by LIBRARY_SCAN (BleScanService) and HOST_SCAN
     * (submitScanResult/submitServiceData): dedup -> location -> enqueue -> emit.
     * [explicitLocation] overrides the library's own location lookup when the caller already has one.
     */
    internal fun internalOnScanResult(
        eidHex: String, payloadHex: String, rssi: Int, timestamp: String,
        bleAddress: String? = null,
        explicitLocation: Location? = null,
    ) {
        sdkScope.launch {
            // Suppress only an exact repeated broadcast from the same source. Including the
            // payload is essential: a changed payload from the same BLE MAC is new data and
            // must reach ReportManager so it can replace that source's pending_latest row.
            val physicalKey = bleAddress?.takeIf { it.isNotBlank() } ?: eidHex
            val dedupKey = "$physicalKey:$payloadHex"
            val isDuplicate = deduplicator.isDuplicate(dedupKey)
            val event = ScanEvent(eidHex = eidHex, rssi = rssi,
                timestamp = System.currentTimeMillis(), reported = !isDuplicate)
            _scanEvents.emit(event)

            val status: String
            if (!isDuplicate) {
                val location = explicitLocation ?: locationHelper.getLocation()
                val usableLocation = location?.takeIf {
                    it.hasAccuracy() && it.accuracy.isFinite() && it.accuracy >= 0f &&
                        it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
                        it.longitude.isFinite() && it.longitude in -180.0..180.0
                }
                val lat = usableLocation?.latitude
                val lon = usableLocation?.longitude
                val accuracy = usableLocation?.accuracy?.toDouble()
                val locationSource = when {
                    usableLocation == null -> "unknown"
                    explicitLocation != null -> "host_supplied"
                    else -> "sdk_fused"
                }
                val locationIsMock = usableLocation?.let(LocationCompat::isMock) ?: false
                val sourceKey = SourceKeyFactory.create(bleAddress, eidHex, prefs.sourceKeyHmacKey)
                status = when (reportManager.enqueue(
                    sourceKey, eidHex, payloadHex, rssi, lat, lon, accuracy,
                    locationSource, locationIsMock, timestamp,
                )) {
                    EnqueueResult.Queued -> "Queued"
                    EnqueueResult.QueueFull -> "QueueFull"
                    EnqueueResult.Invalid -> "Invalid"
                }
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
        hasPermissions: Boolean? = null,
    ) {
        _scanState.update { current ->
            current.copy(
                isScanning = isScanning ?: current.isScanning,
                bleEnabled = bleEnabled ?: current.bleEnabled,
                gpsEnabled = gpsEnabled ?: current.gpsEnabled,
                hasPermissions = hasPermissions ?: current.hasPermissions,
            )
        }
    }

    private fun addScanLog(eidPrefix: String, rssi: Int, status: String) {
        val entry = ScanLogEntry(
            time = logTimeFormat.format(LocalTime.now()),
            eidPrefix = eidPrefix,
            rssi = rssi,
            status = status,
        )
        _scanLogs.update { current ->
            listOf(entry) + current.take(99)
        }
    }

    // Flip queued entries to Accepted after a matching 202. This is not a tag-valid verdict.
    private fun markReported(eidPrefixes: List<String>) {
        if (eidPrefixes.isEmpty()) return
        val prefixes = eidPrefixes.toSet()
        _scanLogs.update { current ->
            current.map {
                if (it.status == "Queued" && it.eidPrefix in prefixes) it.copy(status = "Accepted") else it
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            BleScanService.CHANNEL_ID,
            "NanoBeaconNetwork Scanning",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "BLE scanning service" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
