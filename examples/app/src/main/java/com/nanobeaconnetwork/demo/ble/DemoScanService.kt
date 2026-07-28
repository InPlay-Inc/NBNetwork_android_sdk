package com.nanobeaconnetwork.demo.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import java.util.UUID

/**
 * Host-owned BLE scan service (HOST_SCAN-mode demo).
 *
 * This lives in the sample APP, not the SDK: it demonstrates a host app that runs its own
 * BluetoothLeScanner (with the required 0xFC32 filter) and feeds every result to
 * [NbnClient.submitScanResult]. The SDK is configured with ScanSource.HOST_SCAN and never touches
 * BLE itself. A real customer that already scans BLE would simply add the submit call to their
 * existing scan callback instead of standing up a dedicated service like this.
 */
class DemoScanService : Service() {
    companion object {
        private const val TAG = "DemoScanService"

        // NanoBeaconNetwork beacon Service UUID. The host scanner must include this filter to see beacons.
        val SERVICE_UUID: ParcelUuid = ParcelUuid(UUID.fromString("0000FC32-0000-1000-8000-00805F9B34FB"))
        const val ACTION_STOP = "com.nanobeaconnetwork.demo.STOP_SCAN"
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "demo_scan"

        fun start(context: Context) {
            val intent = Intent(context, DemoScanService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DemoScanService::class.java).apply { action = ACTION_STOP })
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    // Whether this service instance already has an active scan registered with scanCallback.
    // Guards against re-registering (which yields SCAN_FAILED_ALREADY_STARTED) when the
    // foreground service survives the app being closed and onStartCommand runs again.
    @Volatile private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Host business logic could run here; then feed the SDK. Non-0xFC32 results are
            // ignored SDK-side, so it is safe to forward everything the host receives.
            NbnClient.submitScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { NbnClient.submitScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) {
                // Not a real failure: a scan with this callback is already running (e.g. the
                // service survived the app being closed). Keep reporting as scanning.
                Log.d(TAG, "Scan already started; keeping existing scan")
                scanning = true
                DemoScanController.update(isScanning = true, bleEnabled = true)
                return
            }
            Log.e(TAG, "Scan failed: $errorCode")
            scanning = false
            DemoScanController.update(isScanning = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopScan()
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        startBle()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startBle() {
        // Already scanning on this service instance — don't re-register (would fail with
        // SCAN_FAILED_ALREADY_STARTED). Happens when the surviving service gets another
        // onStartCommand as the app is reopened.
        if (scanning) {
            DemoScanController.update(isScanning = true, bleEnabled = true)
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            DemoScanController.update(isScanning = false, bleEnabled = false)
            return
        }
        // The host scanner MUST include the 0xFC32 filter to see NanoBeaconNetwork beacons. A host that
        // already has its own filters would append this one (filters are OR-combined), not
        // replace them.
        val filter = ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(5000L)
            .build()
        adapter.bluetoothLeScanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true
        DemoScanController.update(isScanning = true, bleEnabled = true)
        Log.i(TAG, "Host BLE scan started (HOST_SCAN mode), feeding NbnClient.submitScanResult()")
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop scan: ${e.message}")
        }
        scanning = false
        DemoScanController.update(isScanning = false)
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        val stats = NbnClient.reportStats.value
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NanoBeaconNetwork Demo Scanning")
            .setContentText("Scanned: ${stats.todayScanCount} | Reported: ${stats.todayReportCount}")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Demo Scanning", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NbnDemo:ScanWakeLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
