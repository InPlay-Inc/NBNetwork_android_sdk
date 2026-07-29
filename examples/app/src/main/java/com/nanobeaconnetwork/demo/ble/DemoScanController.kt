package com.nanobeaconnetwork.demo.ble

import android.content.Context
import com.nanobeaconnetwork.model.ScanState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Host-side scan controller for the HOST_SCAN-mode demo.
 *
 * In HOST_SCAN mode the library never touches BLE; the sample app (the "host") owns scanning and
 * feeds results into the library via [com.nanobeaconnetwork.NbnClient.submitScanResult]. This object holds
 * the host's own scanning state for the UI and starts/stops the host foreground scan service.
 */
object DemoScanController {
    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    val isScanning: Boolean get() = _scanState.value.isScanning

    fun start(context: Context) = DemoScanService.start(context.applicationContext)

    fun stop(context: Context) = DemoScanService.stop(context.applicationContext)

    internal fun update(
        isScanning: Boolean? = null,
        bleEnabled: Boolean? = null,
        gpsEnabled: Boolean? = null,
        hasPermissions: Boolean? = null,
    ) {
        _scanState.value = _scanState.value.copy(
            isScanning = isScanning ?: _scanState.value.isScanning,
            bleEnabled = bleEnabled ?: _scanState.value.bleEnabled,
            gpsEnabled = gpsEnabled ?: _scanState.value.gpsEnabled,
            hasPermissions = hasPermissions ?: _scanState.value.hasPermissions,
        )
    }
}
