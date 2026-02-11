package zaujaani.roadsense.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "telemetry_raw",
    foreignKeys = [
        ForeignKey(
            entity = SurveySession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("sessionId"),
        Index("timestamp"),
        Index("quality")
    ]
)
data class TelemetryRaw(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val speed: Float,
    val vibrationZ: Float,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val gpsAccuracy: Float?,
    val quality: String, // "HIGH", "MEDIUM", "LOW"
    val flags: String, // JSON array flag seperti ["HIGH_CONFIDENCE", "GPS_UNAVAILABLE"]
    val pulseCount: Int? = null, // Pulse count dari Hall sensor
    val hallSensorDistance: Float? = null, // Jarak dari Hall sensor
    val temperature: Float? = null, // Suhu jika sensor ada
    val humidity: Float? = null // Kelembaban jika sensor ada
)