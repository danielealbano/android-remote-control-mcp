package com.danielealbano.androidremotecontrolmcp.services.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "MCP:ReverseGeocoder"

/** Framework-only (GMS-free) reverse geocoding shared by all LocationProvider implementations. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun reverseGeocode(
    context: Context,
    latitude: Double,
    longitude: Double,
): String? {
    if (!Geocoder.isPresent()) {
        Log.d(TAG, "Geocoder not present on this device")
        return null
    }
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                Geocoder(context, Locale.getDefault()).getFromLocation(
                    latitude,
                    longitude,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: List<Address>) {
                            if (cont.isActive) {
                                cont.resume(addresses.firstOrNull()?.getAddressLine(0))
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            Log.d(TAG, "Geocoder onError: $errorMessage")
                            if (cont.isActive) {
                                cont.resume(null)
                            }
                        }
                    },
                )
            }
        } else {
            // Android 12 (API 31/32) port: the GeocodeListener overload of
            // Geocoder.getFromLocation is API 33+; use the classic synchronous
            // overload below 33 (deprecated on 33+ but functional everywhere).
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.getDefault())
                    .getFromLocation(latitude, longitude, 1)
                    ?.firstOrNull()
                    ?.getAddressLine(0)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "Reverse geocoding failed: ${e.message}")
        null
    }
}
