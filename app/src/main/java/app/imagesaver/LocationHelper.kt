package app.imagesaver

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Best-effort current location. Returns null on any failure; caller applies a timeout. */
object LocationHelper {
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): GpsCoordinates? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstOrNull { lm.isProviderEnabled(it) } ?: return lastKnown(lm)
            val fresh = suspendCancellableCoroutine<Location?> { cont ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(provider, signal, context.mainExecutor) { if (cont.isActive) cont.resume(it) }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (cont.isActive) cont.resume(location)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
            fresh?.let { GpsCoordinates(it.latitude, it.longitude) } ?: lastKnown(lm)
        } catch (e: SecurityException) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): GpsCoordinates? = try {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
            ?.let { GpsCoordinates(it.latitude, it.longitude) }
    } catch (e: SecurityException) {
        null
    }
}
