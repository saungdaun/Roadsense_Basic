package zaujaani.roadsense.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "road_segments",
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
        Index("roadName"),
        Index("condition"),
        Index("surface"),
        Index("confidence"),
        Index("qualityScore")
    ]
)
data class RoadSegment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val roadName: String,
    val startLatitude: Double,
    val startLongitude: Double,
    val endLatitude: Double,
    val endLongitude: Double,
    val distanceMeters: Float,
    val condition: String,
    val surface: String,
    val confidence: String,
    val avgSpeed: Float,
    val avgAccuracy: Float,
    val avgVibration: Float,
    val qualityScore: Float = 0.0f, // Score kualitas 0.0-1.0 sesuai section 5.5
    val timestamp: Long = System.currentTimeMillis(),
    val surveyorId: String,
    val notes: String? = null,
    val severity: Int = 0,
    val dataSource: String = "SENSOR_PRIMARY", // Sesuai prinsip sensor utama
    val hallSensorDistance: Float? = null, // Jarak dari Hall sensor (jika ada)
    val gpsDistance: Float? = null, // Jarak dari GPS (hanya referensi)
    val photoPaths: String? = null,
    val voiceNotePath: String? = null,
    val tags: String? = null,
    val flags: String? = null, // JSON string dari List<QualityFlag> sesuai section 5.5
    val manualOverride: Boolean = false,
    val needsReview: Boolean = false,
    val excludedFromAnalysis: Boolean = false
)