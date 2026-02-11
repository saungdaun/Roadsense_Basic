package zaujaani.roadsense.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Survey Entity - Menyimpan metadata survey session
 */
@Entity(tableName = "surveys")
data class SurveyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sessionId: String,          // Unique session identifier dari ESP32
    val startTime: Long,            // Unix timestamp (ms)
    val endTime: Long? = null,      // Unix timestamp (ms), null jika masih running

    val totalDistance: Double = 0.0,      // Total distance in meters (dari encoder)
    val totalDistanceGPS: Double = 0.0,   // Total distance dari GPS (backup)

    val avgSpeed: Double = 0.0,           // Average speed (km/h)
    val maxSpeed: Double = 0.0,           // Max speed (km/h)

    val totalRoughness: Double = 0.0,     // Cumulative IRI value
    val avgRoughness: Double = 0.0,       // Average IRI
    val maxRoughness: Double = 0.0,       // Max IRI

    val dataPointCount: Int = 0,          // Jumlah data points yang terekam

    val status: String = "RUNNING",       // RUNNING, PAUSED, STOPPED, COMPLETED
    val roadName: String? = null,         // Nama jalan (optional)
    val notes: String? = null,            // Catatan tambahan

    val wheelCircumference: Double = 2.0, // Wheel circumference yang dipakai (meter)
    val accelZOffset: Double = 0.0,       // Z-axis offset calibration

    // Quality metrics
    val qualityScore: Double = 0.0,       // Overall quality score (0-100)
    val gpsDropCount: Int = 0,            // Berapa kali GPS drop
    val btDisconnectCount: Int = 0,       // Berapa kali Bluetooth disconnect

    // Geographic bounds
    val minLatitude: Double? = null,
    val maxLatitude: Double? = null,
    val minLongitude: Double? = null,
    val maxLongitude: Double? = null
)