package zaujaani.roadsense.core.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import zaujaani.roadsense.core.events.RealtimeRoadsenseBus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GPSGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: RealtimeRoadsenseBus
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
        private const val MIN_TIME_MS = 1000L
        private const val MIN_DISTANCE_M = 0f
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        try {
            if (locationManager == null) {
                _gpsStatus.value = GPSStatus.Disabled
                return
            }
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                _gpsStatus.value = GPSStatus.Disabled
                return
            }
            _gpsStatus.value = GPSStatus.Searching
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                this
            )
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                _currentLocation.value = it
                bus.publishGpsLocation(it)
                _gpsStatus.value = GPSStatus.Available(it.accuracy)
            }
            Timber.d("🛰️ GPS tracking started")
        } catch (e: SecurityException) {
            _gpsStatus.value = GPSStatus.Unavailable("Permission denied")
        } catch (e: Exception) {
            _gpsStatus.value = GPSStatus.Unavailable(e.message ?: "Unknown error")
        }
    }

    fun stopTracking() {
        try {
            locationManager?.removeUpdates(this)
            _gpsStatus.value = GPSStatus.Disabled
            bus.publishGpsLocation(null)
            Timber.d("🛑 GPS stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping GPS")
        }
    }

    override fun onLocationChanged(location: Location) {
        _currentLocation.value = location
        _gpsStatus.value = GPSStatus.Available(location.accuracy)
        bus.publishGpsLocation(location)
        Timber.v("📍 GPS: acc=${location.accuracy}m")
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) {
        _gpsStatus.value = GPSStatus.Searching
    }

    override fun onProviderDisabled(provider: String) {
        _gpsStatus.value = GPSStatus.Disabled
        bus.publishGpsLocation(null)
    }

    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    fun isGPSAvailable(): Boolean =
        locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
}