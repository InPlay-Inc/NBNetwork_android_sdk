package com.nanobeaconnetwork.demo.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.nanobeaconnetwork.NbnClient
import com.nanobeaconnetwork.demo.ble.DemoScanController
import com.nanobeaconnetwork.model.ReportStats
import com.nanobeaconnetwork.model.ScanLogEntry
import com.nanobeaconnetwork.model.ScanState
import kotlinx.coroutines.flow.StateFlow

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    // HOST_SCAN mode: scanning state comes from the host-owned scanner, not the library.
    val scanState: StateFlow<ScanState> = DemoScanController.scanState
    // Report stats / scan logs still come from the library's reporting pipeline.
    val reportStats: StateFlow<ReportStats> = NbnClient.reportStats
    val scanLogs: StateFlow<List<ScanLogEntry>> = NbnClient.scanLogs

    fun toggleScan() {
        val ctx = getApplication<Application>()
        if (DemoScanController.isScanning) DemoScanController.stop(ctx)
        else DemoScanController.start(ctx)
    }
}
