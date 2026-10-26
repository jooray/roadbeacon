package app.roadbeacon.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class LocationUpdate(
    val lat: Double,
    val lon: Double,
    val speed: Float, // m/s
    val bearing: Float,
    val accuracy: Float,
    val timestamp: Long
)

sealed class LocationStartResult {
    object Success : LocationStartResult()
    object PermissionDenied : LocationStartResult()
    data class Error(val exception: Exception) : LocationStartResult()
}

class LocationMonitor(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _locations = MutableSharedFlow<LocationUpdate>(replay = 1, extraBufferCapacity = 10)
    val locations: SharedFlow<LocationUpdate> = _locations

    private var lastLocation: Location? = null
    private var currentGeohash: String? = null
    private var geohashPrecision: Int = 5

    private val _geohashChanged = MutableSharedFlow<Pair<String, Int>>(replay = 1, extraBufferCapacity = 5)
    val geohashChanged: SharedFlow<Pair<String, Int>> = _geohashChanged

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            processLocation(location)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun start(): LocationStartResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted")
            return LocationStartResult.PermissionDenied
        }

        Log.d(TAG, "Starting location monitoring (minTime=${MIN_TIME_MS}ms, minDist=${MIN_DISTANCE_M}m)")

        // Try to get last known location immediately
        try {
            val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastKnown != null) {
                Log.d(TAG, "Using last known location: lat=${lastKnown.latitude}, lon=${lastKnown.longitude}")
                processLocation(lastKnown)
            } else {
                Log.d(TAG, "No last known location available")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting last known location: ${e.message}")
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                locationListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GPS location updates: ${e.message}")
            return LocationStartResult.Error(e)
        }

        // Also try network provider as fallback
        try {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                locationListener
            )
        } catch (e: Exception) {
            Log.d(TAG, "Network provider not available")
        }

        return LocationStartResult.Success
    }

    fun stop() {
        locationManager.removeUpdates(locationListener)
    }

    fun getLastLocation(): LocationUpdate? {
        val loc = lastLocation ?: return null
        return LocationUpdate(
            lat = loc.latitude,
            lon = loc.longitude,
            speed = loc.speed,
            bearing = loc.bearing,
            accuracy = loc.accuracy,
            timestamp = loc.time
        )
    }

    private fun processLocation(location: Location) {
        val bearing = if (location.hasBearing()) {
            location.bearing
        } else {
            lastLocation?.let { prev ->
                prev.bearingTo(location)
            } ?: 0f
        }

        val update = LocationUpdate(
            lat = location.latitude,
            lon = location.longitude,
            speed = location.speed,
            bearing = bearing,
            accuracy = location.accuracy,
            timestamp = location.time
        )

        Log.d(TAG, "Location: lat=${location.latitude}, lon=${location.longitude}, speed=${location.speed}m/s, accuracy=${location.accuracy}m")

        lastLocation = location
        _locations.tryEmit(update)

        // Determine geohash precision based on speed (with hysteresis to prevent rapid switching)
        val speedKmh = location.speed * 3.6f
        val newPrecision = when {
            geohashPrecision == 5 && speedKmh > 35 -> 4  // Switch to wider area at 35 km/h
            geohashPrecision == 4 && speedKmh < 25 -> 5  // Switch back at 25 km/h
            else -> geohashPrecision                      // Stay in current band
        }
        val newGeohash = GeohashUtil.encode(location.latitude, location.longitude, newPrecision)

        Log.d(TAG, "Geohash: $newGeohash (precision $newPrecision, speed ${speedKmh.toInt()} km/h), current=$currentGeohash")

        if (newGeohash != currentGeohash || newPrecision != geohashPrecision) {
            Log.d(TAG, "Geohash CHANGED: $currentGeohash -> $newGeohash (precision $geohashPrecision -> $newPrecision)")
            currentGeohash = newGeohash
            geohashPrecision = newPrecision
            _geohashChanged.tryEmit(Pair(newGeohash, newPrecision))
        }
    }

    companion object {
        private const val TAG = "LocationMonitor"
        private const val MIN_TIME_MS = 3000L
        private const val MIN_DISTANCE_M = 10f
    }
}
