package zaujaani.roadsense.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Survey DAO - Database Access Object untuk SurveyEntity
 */
@Dao
interface SurveyDao {

    // ============ INSERT ============
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSurvey(survey: SurveyEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetry(telemetry: TelemetryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetryBatch(telemetryList: List<TelemetryEntity>)

    // ============ UPDATE ============
    @Update
    suspend fun updateSurvey(survey: SurveyEntity)

    @Query("UPDATE surveys SET status = :status, endTime = :endTime WHERE id = :surveyId")
    suspend fun updateSurveyStatus(surveyId: Long, status: String, endTime: Long?)

    @Query("""
        UPDATE surveys SET 
        totalDistance = :totalDistance,
        avgSpeed = :avgSpeed,
        maxSpeed = :maxSpeed,
        totalRoughness = :totalRoughness,
        avgRoughness = :avgRoughness,
        maxRoughness = :maxRoughness,
        dataPointCount = :dataPointCount,
        qualityScore = :qualityScore
        WHERE id = :surveyId
    """)
    suspend fun updateSurveyMetrics(
        surveyId: Long,
        totalDistance: Double,
        avgSpeed: Double,
        maxSpeed: Double,
        totalRoughness: Double,
        avgRoughness: Double,
        maxRoughness: Double,
        dataPointCount: Int,
        qualityScore: Double
    )

    // ============ QUERY ============
    @Query("SELECT * FROM surveys WHERE id = :surveyId")
    suspend fun getSurveyById(surveyId: Long): SurveyEntity?

    @Query("SELECT * FROM surveys WHERE id = :surveyId")
    fun getSurveyByIdFlow(surveyId: Long): Flow<SurveyEntity?>

    @Query("SELECT * FROM surveys WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSurveyBySessionId(sessionId: String): SurveyEntity?

    @Query("SELECT * FROM surveys ORDER BY startTime DESC")
    fun getAllSurveysFlow(): Flow<List<SurveyEntity>>

    @Query("SELECT * FROM surveys ORDER BY startTime DESC")
    suspend fun getAllSurveys(): List<SurveyEntity>

    @Query("SELECT * FROM surveys WHERE status = :status ORDER BY startTime DESC")
    fun getSurveysByStatusFlow(status: String): Flow<List<SurveyEntity>>

    @Query("SELECT * FROM surveys WHERE status = 'RUNNING' OR status = 'PAUSED' LIMIT 1")
    suspend fun getActiveSurvey(): SurveyEntity?

    @Query("SELECT * FROM surveys WHERE status = 'RUNNING' OR status = 'PAUSED' LIMIT 1")
    fun getActiveSurveyFlow(): Flow<SurveyEntity?>

    // ============ TELEMETRY QUERIES ============
    @Query("SELECT * FROM telemetry WHERE surveyId = :surveyId ORDER BY timestamp ASC")
    suspend fun getTelemetryBySurvey(surveyId: Long): List<TelemetryEntity>

    @Query("SELECT * FROM telemetry WHERE surveyId = :surveyId ORDER BY timestamp ASC")
    fun getTelemetryBySurveyFlow(surveyId: Long): Flow<List<TelemetryEntity>>

    @Query("SELECT * FROM telemetry WHERE surveyId = :surveyId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastTelemetry(surveyId: Long): TelemetryEntity?

    @Query("SELECT * FROM telemetry WHERE surveyId = :surveyId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTelemetry(surveyId: Long, limit: Int = 10): List<TelemetryEntity>

    @Query("SELECT COUNT(*) FROM telemetry WHERE surveyId = :surveyId")
    suspend fun getTelemetryCount(surveyId: Long): Int

    @Query("SELECT * FROM telemetry WHERE surveyId = :surveyId AND timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    suspend fun getTelemetryByTimeRange(surveyId: Long, startTime: Long, endTime: Long): List<TelemetryEntity>

    // ============ STATISTICS ============
    @Query("SELECT AVG(speedFromEncoder) FROM telemetry WHERE surveyId = :surveyId")
    suspend fun getAverageSpeed(surveyId: Long): Double?

    @Query("SELECT MAX(speedFromEncoder) FROM telemetry WHERE surveyId = :surveyId")
    suspend fun getMaxSpeed(surveyId: Long): Double?

    @Query("SELECT AVG(iri) FROM telemetry WHERE surveyId = :surveyId")
    suspend fun getAverageIRI(surveyId: Long): Double?

    @Query("SELECT MAX(iri) FROM telemetry WHERE surveyId = :surveyId")
    suspend fun getMaxIRI(surveyId: Long): Double?

    @Query("SELECT SUM(iri) FROM telemetry WHERE surveyId = :surveyId")
    suspend fun getTotalRoughness(surveyId: Long): Double?

    // ============ DELETE ============
    @Delete
    suspend fun deleteSurvey(survey: SurveyEntity)

    @Query("DELETE FROM surveys WHERE id = :surveyId")
    suspend fun deleteSurveyById(surveyId: Long)

    @Query("DELETE FROM telemetry WHERE surveyId = :surveyId")
    suspend fun deleteTelemetryBySurvey(surveyId: Long)

    @Query("DELETE FROM surveys")
    suspend fun deleteAllSurveys()

    @Query("DELETE FROM telemetry")
    suspend fun deleteAllTelemetry()

    // ============ TRANSACTION ============
    @Transaction
    suspend fun deleteSurveyWithTelemetry(surveyId: Long) {
        deleteTelemetryBySurvey(surveyId)
        deleteSurveyById(surveyId)
    }
}