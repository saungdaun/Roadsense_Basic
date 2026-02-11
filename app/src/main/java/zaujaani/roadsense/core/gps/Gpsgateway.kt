package zaujaani.roadsense.core.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GPS Gateway - Mengelola GPS location tracking
 *
 * CATATAN PENTING:
 * GPS adalah BACKUP untuk posisi geografis saja.
 * Jarak utama tetap dari sensor (encoder).
 */
@Singleton
class GPSGateway @Inject constructor(
    private val context: Context
) : LocationListener {

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _gpsStatus = MutableStateFlow<GPSStatus>(GPSStatus.Disabled)
    val gpsStatus: StateFlow<GPSStatus> = _gpsStatus.asStateFlow()

    sealed class GPSStatus {
        object Disabled : GPSStatus()
        object Searching : GPSStatus()
        data class Available(val accuracy: Float) : GPSStatus()
        data class Unavailable(val reason: String) : GPSStatus()
    }

    companion object {
        private const val MIN_TIME_MS = 1000L // Update every 1 second
        private const val MIN_DISTANCE_M = 0f  // Update on any movement
    }

    /**
     * Start GPS tracking
     */
    @SuppressLint("MissingPermission")
    fun startTracking() {
        try {
            if (locationManager == null) {
                _gpsStatus.value = GPSStatus.Disabled
                Timber.e("❌ LocationManager is null")
                return
            }

            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                _gpsStatus.value = GPSStatus.Disabled
                Timber.w("⚠️ GPS is disabled")
                return
            }

            _gpsStatus.value = GPSStatus.Searching

            // Request location updates
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                this
            )

            // Get last known location
            val lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastLocation != null) {
                _currentLocation.value = lastLocation
                _gpsStatus.value = GPSStatus.Available(lastLocation.accuracy)
                Timber.d("📍 Last known location: ${lastLocation.latitude}, ${lastLocation.longitude}")
            }

            Timber.d("🛰️ GPS tracking started")
        } catch (e: SecurityException) {
            Timber.e(e, "❌ GPS permission denied")
            _gpsStatus.value = GPSStatus.Unavailable("Permission denied")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to start GPS tracking")
            _gpsStatus.value = GPSStatus.Unavailable(e.message ?: "Unknown error")
        }
    }

    /**
     * Stop GPS tracking
     */
    fun stopTracking() {
        try {
            locationManager?.removeUpdates(this)
            _gpsStatus.value = GPSStatus.Disabled
            Timber.d("🛑 GPS tracking stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping GPS tracking")
        }
    }

    /**
     * LocationListener callbacks
     */
    override fun onLocationChanged(location: Location) {
        _currentLocation.value = location
        _gpsStatus.value = GPSStatus.Available(location.accuracy)

        Timber.v(
            "📍 GPS Update: " +
                    "lat=${location.latitude}, " +
                    "lon=${location.longitude}, " +
                    "acc=${location.accuracy}m, " +
                    "speed=${location.speed}m/s"
        )
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        Timber.d("GPS status changed: provider=$provider, status=$status")
    }

    override fun onProviderEnabled(provider: String) {
        Timber.d("✅ GPS provider enabled: $provider")
        _gpsStatus.value = GPSStatus.Searching
    }

    override fun onProviderDisabled(provider: String) {
        Timber.w("⚠️ GPS provider disabled: $provider")
        _gpsStatus.value = GPSStatus.Disabled
    }

    /**
     * Calculate distance between two locations (in meters)
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    /**
     * Check if GPS is available
     */
    fun isGPSAvailable(): Boolean {
        return locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    }
}