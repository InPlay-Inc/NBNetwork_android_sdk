package com.nanobeaconnetwork.demo

import android.app.Application
import android.content.SharedPreferences
import com.nanobeaconnetwork.NbnConfig
import com.nanobeaconnetwork.NbnClient

class DemoApp : Application() {
    lateinit var appPrefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        appPrefs = getSharedPreferences("demo_prefs", MODE_PRIVATE)

        val serverUrl = appPrefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

        val config = NbnConfig.Builder()
            .serverUrl(serverUrl)
            // HOST_SCAN: the app owns BLE scanning (see DemoScanService) and feeds results via
            // NbnClient.submitScanResult(); the SDK never touches BLE itself. (The SDK default is
            // SDK_SCAN, where the SDK runs its own scan service — this sample overrides it to
            // demonstrate host-owned scanning.)
            .scanSource(NbnConfig.ScanSource.HOST_SCAN)
            .build()

        NbnClient.init(this, config)
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://api.nanobeaconnetwork.com"
    }
}
