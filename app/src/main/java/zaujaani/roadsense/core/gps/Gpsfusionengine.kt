package zaujaani.roadsense.core.gps

import android.location.Location
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * GPS Fusion Engine
 *
 * Handles GPS interpolation saat sinyal hilang menggunakan dead reckoning
 * Principle: Sensor distance + last known location + bearing → estimated position
 *
 * ⚠️ IMPORTANT: GPS adalah referensi posisi ONLY, bukan source jarak!
 * Jarak tetap dari sensor, coordinate di-interpolate berdasarkan jarak sensor.
 *
 * Algorithm: Dead Reckoning dengan Haversine formula
 */
@Singleton
class GPSFusionEngine @Inject constructor() {

    companion object {
        private const val EARTH_RADIUS_METERS = 6371000.0  // Earth radius in meters
        private const val GPS_GOOD_ACCURACY = 20f          // meters - good GPS fix
        private const val GPS_ACCEPTABLE_ACCURACY = 50f    // meters - acceptable
        private const val MAX_INTERPOLATION_DISTANCE = 500f // meters - max distance to interpolate
        private const val DEFAULT_BEARING = 0f             // North - if no bearing available
        private const val INTERPOLATED_ACCURACY = 100f     // Interpolated position accuracy (conservative)
    }

    private var lastKnownLocation: Location? = null
    private var lastKnownBearing: Float = DEFAULT_BEARING
    private var totalInterpolatedDistance: Float = 0f
    private var lastGoodGPSTime: Long = 0L

    data class FusionState(
        val isGPSAvailable: Boolean,
        val lastGoodGPSTime: Long,
        val interpolatedDistance: Float,
        val confidence: Float
    )

    /**
     * Update dengan GPS fix yang bagus
     */
    fun updateGoodGPSFix(location: Location) {
        if (isGoodGPSFix(location)) {
            lastKnownLocation = location
            if (location.hasBearing() && location.bearing != 0f) {
                lastKnownBearing = location.bearing
            }
            lastGoodGPSTime = System.currentTimeMillis()
            totalInterpolatedDistance = 0f  // Reset interpolation counter

            Timber.v("✅ GPS fix updated: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m, bearing=${lastKnownBearing}°")
        } else {
            Timber.w("⚠️ GPS fix quality poor (accuracy: ${location.accuracy}m)")
        }
    }

    /**
     * Interpolate position based on distance traveled
     *
     * @param distanceTraveled Distance in meters (dari sensor!)
     * @param currentBearing Optional bearing dari sensor/compass (jika ada)
     * @return Interpolated location atau null jika tidak bisa interpolate
     */
    fun interpolateLocation(
        distanceTraveled: Float,
        currentBearing: Float? = null
    ): Location? {
        val last = lastKnownLocation ?: run {
            Timber.w("⚠️ Cannot interpolate: no last known location")
            return null
        }

        // Check if interpolation distance too far (unreliable)
        if (totalInterpolatedDistance + distanceTraveled > MAX_INTERPOLATION_DISTANCE) {
            Timber.w("⚠️ Cannot interpolate: distance too far (${totalInterpolatedDistance + distanceTraveled}m > ${MAX_INTERPOLATION_DISTANCE}m)")
            return null
        }

        // Use provided bearing or last known bearing
        val bearing = currentBearing ?: lastKnownBearing

        // Calculate new position using Haversine formula
        val newLocation = calculateNewPosition(
            lat = last.latitude,
            lon = last.longitude,
            distanceMeters = distanceTraveled,
            bearingDegrees = bearing
        )

        totalInterpolatedDistance += distanceTraveled

        Timber.d("📍 GPS interpolated: distance=${String.format("%.1f", distanceTraveled)}m, bearing=${String.format("%.1f", bearing)}°, total_interpolated=${String.format("%.1f", totalInterpolatedDistance)}m")

        return newLocation
    }

    /**
     * Calculate new position using Haversine formula
     *
     * Formula:
     * lat2 = asin(sin(lat1)*cos(d/R) + cos(lat1)*sin(d/R)*cos(bearing))
     * lon2 = lon1 + atan2(sin(bearing)*sin(d/R)*cos(lat1), cos(d/R) - sin(lat1)*sin(lat2))
     */
    private fun calculateNewPosition(
        lat: Double,
        lon: Double,
        distanceMeters: Float,
        bearingDegrees: Float
    ): Location {
        val lat1Rad = Math.toRadians(lat)
        val lon1Rad = Math.toRadians(lon)
        val bearingRad = Math.toRadians(bearingDegrees.toDouble())

        val angularDistance = distanceMeters / EARTH_RADIUS_METERS

        // Calculate new latitude
        val lat2Rad = asin(
            sin(lat1Rad) * cos(angularDistance) +
                    cos(lat1Rad) * sin(angularDistance) * cos(bearingRad)
        )

        // Calculate new longitude
        val lon2Rad = lon1Rad + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(lat1Rad),
            cos(angularDistance) - sin(lat1Rad) * sin(lat2Rad)
        )

        return Location("fused").apply {
            latitude = Math.toDegrees(lat2Rad)
            longitude = Math.toDegrees(lon2Rad)
            accuracy = INTERPOLATED_ACCURACY  // Conservative accuracy estimate
            time = System.currentTimeMillis()
            bearing = bearingDegrees

            // Mark as interpolated (provider name indicates this)
            provider = "fused-interpolated"
        }
    }

    /**
     * Get atau interpolate location
     *
     * @param sensorDistance Distance dari sensor (dalam meter)
     * @param gpsLocation Current GPS location (nullable)
     * @param bearing Optional bearing
     * @return Best available location (GPS or interpolated)
     */
    fun getOrInterpolateLocation(
        sensorDistance: Float,
        gpsLocation: Location?,
        bearing: Float? = null
    ): LocationResult {
        // Case 1: GPS available dan good quality
        if (gpsLocation != null && isGoodGPSFix(gpsLocation)) {
            updateGoodGPSFix(gpsLocation)
            return LocationResult.GPS(gpsLocation)
        }

        // Case 2: GPS available tapi poor quality, interpolate lebih reliable
        if (gpsLocation != null && !isGoodGPSFix(gpsLocation)) {
            val interpolated = interpolateLocation(sensorDistance, bearing)
            if (interpolated != null) {
                return LocationResult.Interpolated(interpolated, sensorDistance)
            }
            // Fallback to poor GPS if interpolation failed
            return LocationResult.PoorGPS(gpsLocation)
        }

        // Case 3: No GPS, interpolate
        val interpolated = interpolateLocation(sensorDistance, bearing)
        if (interpolated != null) {
            return LocationResult.Interpolated(interpolated, sensorDistance)
        }

        // Case 4: Cannot interpolate
        return LocationResult.Unavailable
    }

    /**
     * Check if GPS fix is good quality
     */
    fun isGoodGPSFix(location: Location): Boolean {
        return location.accuracy < GPS_GOOD_ACCURACY &&
                location.hasAccuracy() &&
                location.time > System.currentTimeMillis() - 5000  // < 5 seconds old
    }

    /**
     * Check if GPS fix is acceptable
     */
    fun isAcceptableGPSFix(location: Location): Boolean {
        return location.accuracy < GPS_ACCEPTABLE_ACCURACY &&
                location.hasAccuracy() &&
                location.time > System.currentTimeMillis() - 10000  // < 10 seconds old
    }

    /**
     * Get current fusion state
     */
    fun getFusionState(): FusionState {
        return FusionState(
            isGPSAvailable = lastKnownLocation != null,
            lastGoodGPSTime = lastGoodGPSTime,
            interpolatedDistance = totalInterpolatedDistance,
            confidence = calculateConfidence()
        )
    }

    /**
     * Calculate confidence based on interpolation distance
     */
    private fun calculateConfidence(): Float {
        if (lastKnownLocation == null) return 0f
        if (totalInterpolatedDistance == 0f) return 1.0f

        // Confidence decreases linearly with distance
        val confidence = 1.0f - (totalInterpolatedDistance / MAX_INTERPOLATION_DISTANCE)
        return confidence.coerceIn(0f, 1f)
    }

    /**
     * Reset fusion state (call saat start new survey)
     */
    fun reset() {
        lastKnownLocation = null
        lastKnownBearing = DEFAULT_BEARING
        totalInterpolatedDistance = 0f
        lastGoodGPSTime = 0L
        Timber.d("GPS Fusion Engine reset")
    }

    /**
     * Get time since last good GPS
     */
    fun getTimeSinceLastGoodGPS(): Long {
        return if (lastGoodGPSTime > 0) {
            System.currentTimeMillis() - lastGoodGPSTime
        } else {
            -1L
        }
    }
}

/**
 * Result dari fusion process
 *
 * Cleaner approach:
 * - No manual getters
 * - Abstract property
 * - Smart casting friendly
 */
sealed class LocationResult {

    abstract val location: Location?

    data class GPS(
        override val location: Location
    ) : LocationResult()

    data class PoorGPS(
        override val location: Location
    ) : LocationResult()

    data class Interpolated(
        override val location: Location,
        val basedOnDistance: Float
    ) : LocationResult()

    object Unavailable : LocationResult() {
        override val location: Location? = null
    }

    val isInterpolated: Boolean
        get() = this is Interpolated

    val accuracy: Float?
        get() = location?.accuracy
}
