package com.nanobeaconnetwork.demo

import android.app.Application
import android.content.SharedPreferences
import com.nanobeaconnetwork.NbnConfig
import com.nanobeaconnetwork.NbnSdk

class DemoApp : Application() {
    lateinit var appPrefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        appPrefs = getSharedPreferences("demo_prefs", MODE_PRIVATE)

        val serverUrl = appPrefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

        val config = NbnConfig.Builder()
            .serverUrl(serverUrl)
            // EXTERNAL (also the default): the app owns BLE scanning (see DemoScanService) and
            // feeds results via NbnSdk.submitScanResult(); the SDK never touches BLE itself.
            .scanSource(NbnConfig.ScanSource.EXTERNAL)
            .build()

        NbnSdk.init(this, config)
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://api.nanobeaconnetwork.com"
    }
}
