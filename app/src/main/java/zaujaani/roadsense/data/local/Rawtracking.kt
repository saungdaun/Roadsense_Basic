package zaujaani.roadsense.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "raw_tracking",
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
        Index("source")
    ]
)
data class RawTracking(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val accelZ: Float,
    val timestamp: Long,
    val source: String, // "ESP32_SENSOR", "PHONE_GPS", "PHONE_SENSOR"
    val hallSensorTrip: Float? = null, // Trip distance dari Hall sensor
    val hallSensorSpeed: Float? = null, // Speed dari Hall sensor
    val batteryLevel: Float? = null, // Level battery ESP32 jika ada
    val packetCount: Int = 0,
    val errorCount: Int = 0,
    val quality: String = "UNKNOWN" // "GOOD", "FAIR", "POOR"
)