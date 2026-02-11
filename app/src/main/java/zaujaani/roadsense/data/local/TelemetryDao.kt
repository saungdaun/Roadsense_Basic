package zaujaani.roadsense.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {

    @Insert
    suspend fun insertTelemetry(telemetry: TelemetryRaw): Long

    @Insert
    suspend fun insertBatchTelemetry(telemetryList: List<TelemetryRaw>)

    @Query("SELECT * FROM telemetry_raw WHERE sessionId = :sessionId ORDER BY timestamp")
    fun getTelemetryBySession(sessionId: Long): Flow<List<TelemetryRaw>>

    @Query("SELECT COUNT(*) FROM telemetry_raw WHERE sessionId = :sessionId")
    suspend fun getTelemetryCount(sessionId: Long): Int

    @Query("SELECT * FROM telemetry_raw WHERE sessionId = :sessionId AND quality = :quality")
    fun getTelemetryByQuality(sessionId: Long, quality: String): Flow<List<TelemetryRaw>>

    // ✅ TAMBAHKAN METHOD INI
    @Query("SELECT * FROM telemetry_raw WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestTelemetry(sessionId: Long): TelemetryRaw?

    @Query("DELETE FROM telemetry_raw WHERE sessionId = :sessionId")
    suspend fun deleteTelemetryBySession(sessionId: Long)

    // Statistics queries
    @Query("""
        SELECT 
            MIN(timestamp) as startTime,
            MAX(timestamp) as endTime,
            COUNT(*) as totalRecords,
            AVG(speed) as avgSpeed,
            AVG(vibrationZ) as avgVibration,
            AVG(gpsAccuracy) as avgAccuracy
        FROM telemetry_raw 
        WHERE sessionId = :sessionId
    """)
    suspend fun getTelemetryStats(sessionId: Long): TelemetryStats?
}

data class TelemetryStats(
    val startTime: Long,
    val endTime: Long,
    val totalRecords: Int,
    val avgSpeed: Float,
    val avgVibration: Float,
    val avgAccuracy: Float?
)