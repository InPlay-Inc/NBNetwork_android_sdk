package com.nanobeaconnetwork

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object NbnPermissions {
    fun checkScanPermissions(context: Context): Boolean {
        val required = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Scanning only needs BLUETOOTH_SCAN; the library never connects/bonds, so CONNECT is not required.
            required += Manifest.permission.BLUETOOTH_SCAN
        }
        return required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    /**
     * Whether `ACCESS_BACKGROUND_LOCATION` is granted — only relevant when opting in to
     * [NbnConfig.Builder.restartOnBoot].
     *
     * The library deliberately does **not** declare this permission, so this returns `false` forever
     * until the host app adds `<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />`
     * to its own manifest and the user grants it (on Android 11+ that means picking "Allow all the
     * time" in Settings — the system dialog does not offer it).
     */
    fun checkBackgroundLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getScanPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        // POST_NOTIFICATIONS is deliberately not requested: a foreground service does not need it,
        // and denying it only hides the ongoing notification from the drawer (it still appears in
        // the Task Manager). A host that wants that notification visible declares and requests the
        // permission itself.
        return perms.toTypedArray()
    }
}
