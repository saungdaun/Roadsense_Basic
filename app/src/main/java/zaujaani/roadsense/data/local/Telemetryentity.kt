package zaujaani.roadsense.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Telemetry Entity - Menyimpan setiap data point dari sensor
 * PRINSIP: Sensor adalah sumber jarak utama, GPS hanya backup
 */
@Entity(
    tableName = "telemetry",
    foreignKeys = [
        ForeignKey(
            entity = SurveyEntity::class,
            parentColumns = ["id"],
            childColumns = ["surveyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["surveyId"]), Index(value = ["timestamp"])]
)
data class TelemetryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val surveyId: Long,                   // Foreign key ke SurveyEntity
    val timestamp: Long,                  // Unix timestamp (ms)

    // === PRIMARY DATA SOURCE: SENSOR ===
    val distanceFromEncoder: Double,      // Distance dari wheel encoder (meter)
    val speedFromEncoder: Double,         // Speed dari encoder (km/h)
    val chainage: Double,                 // Chainage/STA (meter)

    // === ROUGHNESS DATA ===
    val accelZ: Double,                   // Raw Z-axis acceleration (m/s²)
    val accelZFiltered: Double,           // Filtered/smoothed Z value
    val iri: Double,                      // International Roughness Index
    val qualityFlag: String,              // EXCELLENT, GOOD, FAIR, POOR, BAD

    // === GPS DATA (BACKUP/REFERENCE) ===
    val latitude: Double? = null,         // GPS latitude (nullable - bisa drop)
    val longitude: Double? = null,        // GPS longitude (nullable - bisa drop)
    val altitude: Double? = null,         // GPS altitude (meter)
    val gpsSpeed: Double? = null,         // GPS speed (km/h) - untuk validasi
    val gpsAccuracy: Float? = null,       // GPS accuracy (meter)
    val gpsAvailable: Boolean = false,    // Flag jika GPS tersedia

    // === DEVICE STATE ===
    val batteryVoltage: Double? = null,   // ESP32 battery voltage
    val systemState: String = "RUNNING",  // RUNNING, PAUSED, STOPPED

    // === DATA VALIDATION ===
    val dataSource: String = "SENSOR",    // SENSOR, GPS_FALLBACK, INTERPOLATED
    val validationFlag: Boolean = true    // True jika data valid
) {
    /**
     * Helper function untuk menentukan kualitas jalan berdasarkan IRI
     */
    fun getRoadQuality(): String {
        return when {
            iri < 2.0 -> "EXCELLENT"
            iri < 4.0 -> "GOOD"
            iri < 6.0 -> "FAIR"
            iri < 8.0 -> "POOR"
            else -> "BAD"
        }
    }

    /**
     * Helper untuk format chainage ke STA notation (e.g., "12+345")
     */
    fun getStaNotation(): String {
        val km = (chainage / 1000).toInt()
        val m = (chainage % 1000).toInt()
        return String.format("%d+%03d", km, m)
    }
}