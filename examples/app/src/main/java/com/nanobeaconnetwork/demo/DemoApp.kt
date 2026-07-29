package com.nanobeaconnetwork.demo

import android.app.Application
import com.nanobeaconnetwork.NbnConfig
import com.nanobeaconnetwork.NbnClient

class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val config = NbnConfig.Builder()
            // HOST_SCAN: the app owns BLE scanning (see DemoScanService) and feeds results via
            // NbnClient.submitScanResult(); the library never touches BLE itself. (The library default is
            // LIBRARY_SCAN, where the library runs its own scan service — this sample overrides it to
            // demonstrate host-owned scanning.)
            .scanSource(NbnConfig.ScanSource.HOST_SCAN)
            .build()

        NbnClient.init(this, config)
    }
}
