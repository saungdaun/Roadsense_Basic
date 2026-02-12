package zaujaani.roadsense.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {

    @Insert
    suspend fun insertTelemetryRaw(telemetry: TelemetryRaw): Long

    @Insert
    suspend fun insertTelemetryRawBatch(telemetryList: List<TelemetryRaw>)

    @Query("SELECT * FROM telemetry_raw WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getTelemetryRawBySession(sessionId: Long): Flow<List<TelemetryRaw>>

    @Query("SELECT * FROM telemetry_raw WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestTelemetryRaw(sessionId: Long): TelemetryRaw?

    @Query("DELETE FROM telemetry_raw WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)

    @Query("SELECT COUNT(*) FROM telemetry_raw WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: Long): Int
}