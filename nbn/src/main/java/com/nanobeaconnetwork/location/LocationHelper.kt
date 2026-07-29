package com.nanobeaconnetwork.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal class LocationHelper(context: Context) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun getLocation(): Location? {
        val last = suspendCancellableCoroutine<Location?> { cont ->
            fusedClient.lastLocation
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }
        if (last != null) return last

        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { cont ->
                fusedClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY, null
                ).addOnSuccessListener { cont.resume(it) }
                 .addOnFailureListener { cont.resume(null) }
            }
        }
    }
}
