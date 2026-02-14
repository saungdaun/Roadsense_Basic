package zaujaani.roadsense.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.*

@Parcelize
data class TrackingPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
) : Parcelable {

    fun isValid(): Boolean {
        return latitude != 0.0 && longitude != 0.0
    }

    fun isHighAccuracy(): Boolean {
        return accuracy > 0 && accuracy < 5f
    }

    /**
     * Calculate distance to another point using Haversine formula
     * Returns distance in meters
     */
    fun distanceTo(other: TrackingPoint): Float {
        val earthRadius = 6371000.0 // meters

        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)

        val lat1Rad = Math.toRadians(latitude)
        val lat2Rad = Math.toRadians(other.latitude)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return (earthRadius * c).toFloat()
    }

    /**
     * Convert to GeoPoint for OSMDroid
     */
    fun toGeoPoint(): org.osmdroid.util.GeoPoint {
        return org.osmdroid.util.GeoPoint(latitude, longitude, altitude)
    }
}