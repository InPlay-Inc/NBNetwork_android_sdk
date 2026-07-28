package com.nanobeaconnetwork.demo.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.nanobeaconnetwork.NbnSdk
import com.nanobeaconnetwork.demo.ble.DemoScanController
import com.nanobeaconnetwork.model.ReportStats
import com.nanobeaconnetwork.model.ScanLogEntry
import com.nanobeaconnetwork.model.ScanState
import kotlinx.coroutines.flow.StateFlow

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    // EXTERNAL mode: scanning state comes from the host-owned scanner, not the SDK.
    val scanState: StateFlow<ScanState> = DemoScanController.scanState
    // Report stats / scan logs still come from the SDK's reporting pipeline.
    val reportStats: StateFlow<ReportStats> = NbnSdk.reportStats
    val scanLogs: StateFlow<List<ScanLogEntry>> = NbnSdk.scanLogs

    fun toggleScan() {
        val ctx = getApplication<Application>()
        if (DemoScanController.isScanning) DemoScanController.stop(ctx)
        else DemoScanController.start(ctx)
    }
}
