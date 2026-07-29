package com.nanobeaconnetwork.internal.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.nanobeaconnetwork.NbnPermissions
import com.nanobeaconnetwork.internal.prefs.NbnPrefs

/**
 * Resumes LIBRARY_SCAN scanning after a device reboot.
 *
 * Starts the foreground scan service only when scanning was active at shutdown
 * (NbnPrefs.scanEnabled, set by NbnClient.startScan/stopScan), the host has not opted out
 * (NbnConfig.restartOnBoot, default true), AND ACCESS_BACKGROUND_LOCATION is granted — without that
 * permission a background-started foreground service cannot access location, so the library does not
 * resume at all rather than collect position-less reports. scanEnabled is only ever set in LIBRARY_SCAN
 * mode, so a HOST_SCAN integration never triggers this.
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
                val prefs = NbnPrefs(context.applicationContext)
                if (prefs.scanEnabled && prefs.restartOnBoot) {
                    if (NbnPermissions.checkBackgroundLocationPermission(context)) {
                        Log.i(TAG, "Resuming scan after boot")
                        BleScanService.start(context.applicationContext)
                    } else if (declaresBackgroundLocation(context)) {
                        // The host wants reboot recovery but the grant is missing or was revoked.
                        Log.w(
                            TAG,
                            "Not resuming after boot: ACCESS_BACKGROUND_LOCATION is declared but not " +
                                "granted. A foreground service started from the background cannot " +
                                "access location without it.",
                        )
                    } else {
                        // Expected for the default integration: the library does not declare the
                        // permission, so a host that never opted in simply gets no reboot recovery.
                        Log.i(TAG, "Boot: reboot recovery unavailable (ACCESS_BACKGROUND_LOCATION not declared)")
                    }
                } else {
                    Log.i(TAG, "Boot: not resuming (scanEnabled=${prefs.scanEnabled}, restartOnBoot=${prefs.restartOnBoot})")
                }
            }
        }
    }

    /**
     * Whether the merged manifest requests `ACCESS_BACKGROUND_LOCATION`. The library deliberately does
     * not declare it, so this is true only when the host app opted in — which is what separates a
     * real misconfiguration (declared but not granted) from the expected default (never declared).
     */
    private fun declaresBackgroundLocation(context: Context): Boolean = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == true
    }.getOrDefault(false)

    private companion object {
        const val TAG = "BootReceiver"
    }
}
