package com.nanobeaconnetwork.demo.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.nanobeaconnetwork.NbnPermissions
import com.nanobeaconnetwork.demo.ble.DemoScanController
import com.nanobeaconnetwork.demo.ui.navigation.AppBottomBar
import com.nanobeaconnetwork.demo.ui.navigation.AppNavHost
import com.nanobeaconnetwork.demo.ui.theme.NbnDemoTheme

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // EXTERNAL mode: the host app owns scanning. Start our own scan service once granted.
        if (NbnPermissions.checkScanPermissions(this)) {
            DemoScanController.update(hasPermissions = true)
            DemoScanController.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start scanning on launch by default: if permissions are already granted, start
        // immediately; otherwise request them and start from the result callback.
        // EXTERNAL mode — the sample app scans itself and feeds NbnSdk.submitScanResult().
        if (NbnPermissions.checkScanPermissions(this)) {
            DemoScanController.update(hasPermissions = true)
            DemoScanController.start(this)
        } else {
            requestPermissions.launch(NbnPermissions.getScanPermissions())
        }

        setContent {
            NbnDemoTheme {
                val navController = rememberNavController()
                Scaffold(bottomBar = { AppBottomBar(navController) }) { innerPadding ->
                    AppNavHost(navController, Modifier.padding(innerPadding))
                }
            }
        }
    }
}
