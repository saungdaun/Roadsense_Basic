package zaujaani.roadsense.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "survey_sessions")
data class SurveySession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val surveyorName: String,
    val startTime: Long,
    val endTime: Long? = null,
    val totalDistance: Float = 0f,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val projectName: String? = null,
    val vehicleType: String? = null,
    val weatherCondition: String? = null,
    val deviceName: String? = null, // Nama device ESP32 yang digunakan
    val firmwareVersion: String? = null, // Versi firmware ESP32
    val calibrationId: Long? = null, // ID kalibrasi yang digunakan
    val sessionQuality: Float = 0.0f, // Score kualitas 0.0-1.0
    val packetCount: Int = 0, // Jumlah packet dari ESP32
    val errorCount: Int = 0, // Jumlah error packet
    val gpsAvailability: Float = 0.0f, // Persentase GPS available
    val notes: String? = null
) {
    val duration: Long
        get() = (endTime ?: System.currentTimeMillis()) - startTime

    val isCompleted: Boolean
        get() = endTime != null
}