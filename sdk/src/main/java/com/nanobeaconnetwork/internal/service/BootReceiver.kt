package com.nanobeaconnetwork.internal.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nanobeaconnetwork.internal.prefs.SdkPrefs

/**
 * Resumes SDK_SCAN scanning after a device reboot.
 *
 * Starts the foreground scan service only when scanning was active at shutdown
 * (SdkPrefs.scanEnabled, set by NbnClient.startScan/stopScan) AND the host opted in
 * (NbnConfig.restartOnBoot, default true). scanEnabled is only ever set in SDK_SCAN mode, so a
 * HOST_SCAN integration never triggers this.
 *
 * Listens to BOOT_COMPLETED (delivered after the user unlocks, when the Keystore-backed
 * EncryptedSharedPreferences and the SQLCipher database are available) — deliberately NOT
 * LOCKED_BOOT_COMPLETED, whose direct-boot phase cannot open credential-encrypted storage.
 * Starting a foreground service from BOOT_COMPLETED is an allowed background-FGS-start exemption.
 */
internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val prefs = SdkPrefs(context.applicationContext)
                if (prefs.scanEnabled && prefs.restartOnBoot) {
                    Log.i(TAG, "Resuming SDK scan after boot")
                    BleScanService.start(context.applicationContext)
                } else {
                    Log.i(TAG, "Boot: not resuming (scanEnabled=${prefs.scanEnabled}, restartOnBoot=${prefs.restartOnBoot})")
                }
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
