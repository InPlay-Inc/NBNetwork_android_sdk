package com.nanobeaconnetwork.internal.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nanobeaconnetwork.NbnClient
import com.nanobeaconnetwork.ble.AdvParser
import com.nanobeaconnetwork.model.ScanEvent
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class BleScanService : Service() {
    companion object {
        private const val TAG = "BleScanService"
        // Set true for diagnostics; view logs with `adb logcat -s BleScanService`. Set back to false once resolved.
        private const val VERBOSE = false
        val SERVICE_UUID: ParcelUuid = ParcelUuid(UUID.fromString("0000FC32-0000-1000-8000-00805F9B34FB"))
        const val ACTION_START = "com.nanobeaconnetwork.START_SCAN"
        const val ACTION_STOP = "com.nanobeaconnetwork.STOP_SCAN"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "nbn_scan"

        fun start(context: Context) {
            val intent = Intent(context, BleScanService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BleScanService::class.java).apply { action = ACTION_STOP })
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private var wakeLock: PowerManager.WakeLock? = null

    private val scanCallback = object : ScanCallback() {
        // Called when reportDelay == 0 (VERBOSE diagnostic mode).
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleScanResult(result, batched = false)

        // Results are delivered here when reportDelay > 0 (batch mode, the power-saving default); its previous absence caused results to be dropped.
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            if (VERBOSE) Log.d(TAG, "onBatchScanResults: ${results.size} results")
            results.forEach { handleScanResult(it, batched = true) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            NbnClient.internalUpdateScanState(isScanning = false)
        }
    }

    private fun handleScanResult(result: ScanResult, batched: Boolean) {
        val record = result.scanRecord
        if (VERBOSE) {
            val uuids = record?.serviceUuids?.joinToString { it.uuid.toString() } ?: "null"
            val sdMap = record?.serviceData?.entries
                ?.joinToString { "${it.key.uuid}=${it.value.toHex()}" } ?: "null"
            Log.d(TAG, "raw${if (batched) "[batch]" else ""} addr=${result.device?.address} rssi=${result.rssi}" +
                " raw=${record?.bytes?.toHex()} serviceUuids=[$uuids] serviceData={$sdMap}")
        }
        if (record == null) {
            if (VERBOSE) Log.d(TAG, "reject: scanRecord == null")
            return
        }
        // Filter 1: 16-bit Service UUID list (AD type 0x03) must contain 0xFC32.
        // ScanFilter already enforces this at the chip; recheck for safety.
        if (record.serviceUuids?.contains(SERVICE_UUID) != true) {
            if (VERBOSE) Log.d(TAG, "reject: serviceUuids does not contain $SERVICE_UUID")
            return
        }
        // Filter 2: Service Data record (AD type 0x16) keyed by 0xFC32 —
        // returning non-null implicitly verifies bytes 6–7 of the ADV are 0x32 0xFC.
        val serviceData = record.getServiceData(SERVICE_UUID)
        if (serviceData == null) {
            if (VERBOSE) Log.d(TAG, "reject: $SERVICE_UUID has no service data (AD 0x16)")
            return
        }
        // Filter 3: AdvParser requires service data >= 23 bytes.
        val advData = AdvParser.parse(serviceData)
        if (advData == null) {
            if (VERBOSE) Log.d(TAG, "reject: parse failed, service data length=${serviceData.size} (needs >=23), hex=${serviceData.toHex()}")
            return
        }
        val eidHex = advData.eid.joinToString("") { "%02x".format(it) }
        val rssi = result.rssi
        if (VERBOSE) Log.d(TAG, "accept: eid=$eidHex payloadLen=${advData.payload.length} rssi=$rssi")

        scope.launch {
            NbnClient.internalOnScanResult(
                eidHex = eidHex,
                payloadHex = advData.payload,
                rssi = rssi,
                timestamp = isoFormat.format(Date()),
                bleAddress = result.device?.address,
            )
            updateNotification()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopScan()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // May be started by the host (startScan) or by BootReceiver after a reboot. On
                // the boot path the host may not have called init() yet, so bring the SDK up
                // from persisted state before any NbnClient access (buildNotification/startBle).
                NbnClient.ensureInitialized(applicationContext)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, buildNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    } else {
                        startForeground(NOTIFICATION_ID, buildNotification())
                    }
                } catch (e: Exception) {
                    // e.g. Android 14 rejects a location-type FGS when location permission was
                    // revoked, or a background FGS start is disallowed. Fail gracefully.
                    Log.e(TAG, "startForeground failed, stopping service: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startBle()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startBle() {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            NbnClient.internalUpdateScanState(isScanning = false, bleEnabled = false)
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build()
        // Use reportDelay=0 in VERBOSE diagnostics (immediate callbacks for real-time logging); otherwise 5s batching to save power (design §4.2).
        val reportDelay = if (VERBOSE) 0L else 5000L
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(reportDelay)
            .build()
        adapter.bluetoothLeScanner.startScan(listOf(filter), settings, scanCallback)
        NbnClient.internalUpdateScanState(isScanning = true, bleEnabled = true)
        Log.i(TAG, "BLE scan started (filter=$SERVICE_UUID, scanMode=LOW_POWER, reportDelay=$reportDelay)")
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try {
            val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            btManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop BLE scan: ${e.message}")
        }

        NbnClient.internalUpdateScanState(isScanning = false)

        if (scope.isActive) {
            scope.cancel()
        }

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake lock: ${e.message}")
        }
        Log.i(TAG, "BLE scan stopped")
    }

    private fun buildNotification(): Notification {
        val stats = NbnClient.reportStats.value
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NanoBeaconNetwork Scanning")
            .setContentText("Scanned: ${stats.todayScanCount} | Reported: ${stats.todayReportCount}")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nbn:ScanWakeLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
