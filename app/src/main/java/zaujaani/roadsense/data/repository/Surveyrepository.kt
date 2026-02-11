package zaujaani.roadsense.data.repository

import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.Flow
import zaujaani.roadsense.data.local.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SurveyRepository @Inject constructor(
    private val surveyDao: SurveyDao,
    private val sessionDao: SessionDao,
    private val roadSegmentDao: RoadSegmentDao,
    private val calibrationDao: CalibrationDao
) {

    // ============ CALIBRATION ============
    suspend fun hasCalibration(): Boolean = calibrationDao.getActiveCalibration() != null

    suspend fun createNewCalibration(calibration: DeviceCalibration): Long =
        calibrationDao.insertCalibration(calibration)

    suspend fun getActiveCalibration(): DeviceCalibration? = calibrationDao.getActiveCalibration()

    fun getAllCalibrations(): Flow<List<DeviceCalibration>> = calibrationDao.getAllCalibrations()

    suspend fun saveCalibration(calibration: DeviceCalibration): Long {
        calibrationDao.deactivateAllCalibrations()
        return if (calibration.id > 0) {
            calibrationDao.updateCalibration(calibration)
            calibrationDao.activateCalibration(calibration.id)
            calibration.id
        } else {
            val id = calibrationDao.insertCalibration(calibration)
            calibrationDao.activateCalibration(id)
            id
        }
    }

    suspend fun deleteCalibration(calibrationId: Long) = calibrationDao.deleteCalibration(calibrationId)

    // ============ SURVEY SESSION ============
    suspend fun createSurveySession(session: SurveySession): Long =
        sessionDao.insertSurveySession(session)

    suspend fun updateSurveySessionStatus(
        sessionId: Long,
        status: String,
        endTime: Long? = null
    ) {
        val session = sessionDao.getSurveySessionById(sessionId)
        if (session != null) {
            val updated = session.copy(
                isActive = status == "ACTIVE",
                endTime = endTime
            )
            sessionDao.updateSurveySession(updated)
        }
    }

    fun getAllSessions(): Flow<List<SurveySession>> = sessionDao.getAllSurveySessions()

    suspend fun getSurveySessionById(sessionId: Long): SurveySession? =
        sessionDao.getSurveySessionById(sessionId)

    suspend fun getActiveSession(): SurveySession? = sessionDao.getActiveSession()

    // ============ ROAD SEGMENT ============
    suspend fun insertRoadSegment(segment: RoadSegment): Long =
        roadSegmentDao.insertRoadSegment(segment)

    fun getAllRoadSegments(): Flow<List<RoadSegment>> = roadSegmentDao.getAllRoadSegments()

    fun getRoadSegmentsBySession(sessionId: Long): Flow<List<RoadSegment>> =
        roadSegmentDao.getRoadSegmentsBySession(sessionId)

    suspend fun getSummaryBySession(sessionId: Long): List<RoadSegmentSummary> {
        val segments = roadSegmentDao.getRoadSegmentsBySessionSync(sessionId)
        val session = sessionDao.getSurveySessionById(sessionId)
        return segments.map { RoadSegmentSummary(it, session) }
    }

    suspend fun deleteRoadSegment(segmentId: Long) = roadSegmentDao.deleteRoadSegmentById(segmentId)

    suspend fun updateRoadSegment(segment: RoadSegment) = roadSegmentDao.updateRoadSegment(segment)

    // ============ LEGACY SURVEY (SURVEY ENTITY) ============
    suspend fun createSurvey(survey: SurveyEntity): Long = surveyDao.insertSurvey(survey)
    suspend fun updateSurvey(survey: SurveyEntity) = surveyDao.updateSurvey(survey)
    suspend fun updateSurveyStatus(surveyId: Long, status: String, endTime: Long? = null) =
        surveyDao.updateSurveyStatus(surveyId, status, endTime)
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
    ) = surveyDao.updateSurveyMetrics(
        surveyId, totalDistance, avgSpeed, maxSpeed,
        totalRoughness, avgRoughness, maxRoughness,
        dataPointCount, qualityScore
    )
    suspend fun getSurveyById(surveyId: Long): SurveyEntity? = surveyDao.getSurveyById(surveyId)
    fun getSurveyByIdFlow(surveyId: Long): Flow<SurveyEntity?> = surveyDao.getSurveyByIdFlow(surveyId)
    suspend fun getSurveyBySessionId(sessionId: String): SurveyEntity? = surveyDao.getSurveyBySessionId(sessionId)
    fun getAllSurveysFlow(): Flow<List<SurveyEntity>> = surveyDao.getAllSurveysFlow()
    suspend fun getAllSurveys(): List<SurveyEntity> = surveyDao.getAllSurveys()
    suspend fun getActiveSurvey(): SurveyEntity? = surveyDao.getActiveSurvey()
    fun getActiveSurveyFlow(): Flow<SurveyEntity?> = surveyDao.getActiveSurveyFlow()
    suspend fun deleteSurvey(surveyId: Long) = surveyDao.deleteSurveyWithTelemetry(surveyId)

    // ============ TELEMETRY (LEGACY) ============
    suspend fun addTelemetryPoint(telemetry: TelemetryEntity): Long = surveyDao.insertTelemetry(telemetry)
    suspend fun addTelemetryBatch(telemetryList: List<TelemetryEntity>) = surveyDao.insertTelemetryBatch(telemetryList)
    suspend fun getTelemetryBySurvey(surveyId: Long): List<TelemetryEntity> = surveyDao.getTelemetryBySurvey(surveyId)
    fun getTelemetryBySurveyFlow(surveyId: Long): Flow<List<TelemetryEntity>> = surveyDao.getTelemetryBySurveyFlow(surveyId)
    suspend fun getLastTelemetry(surveyId: Long): TelemetryEntity? = surveyDao.getLastTelemetry(surveyId)
    suspend fun getRecentTelemetry(surveyId: Long, limit: Int = 10): List<TelemetryEntity> = surveyDao.getRecentTelemetry(surveyId, limit)
    suspend fun getTelemetryCount(surveyId: Long): Int = surveyDao.getTelemetryCount(surveyId)

    // ============ STATISTICS ============
    suspend fun calculateSurveyStatistics(surveyId: Long) {
        val avgSpeed = surveyDao.getAverageSpeed(surveyId) ?: 0.0
        val maxSpeed = surveyDao.getMaxSpeed(surveyId) ?: 0.0
        val avgIRI = surveyDao.getAverageIRI(surveyId) ?: 0.0
        val maxIRI = surveyDao.getMaxIRI(surveyId) ?: 0.0
        val totalRoughness = surveyDao.getTotalRoughness(surveyId) ?: 0.0
        val dataPointCount = surveyDao.getTelemetryCount(surveyId)
        val lastTelemetry = surveyDao.getLastTelemetry(surveyId)
        val totalDistance = lastTelemetry?.distanceFromEncoder ?: 0.0
        val qualityScore = calculateQualityScore(avgIRI, dataPointCount)

        surveyDao.updateSurveyMetrics(
            surveyId, totalDistance, avgSpeed, maxSpeed,
            totalRoughness, avgIRI, maxIRI,
            dataPointCount, qualityScore
        )
    }

    @WorkerThread
    private fun calculateQualityScore(avgIRI: Double, dataPoints: Int): Double {
        val iriScore = when {
            avgIRI < 2.0 -> 100.0
            avgIRI < 4.0 -> 80.0
            avgIRI < 6.0 -> 60.0
            avgIRI < 8.0 -> 40.0
            else -> 20.0
        }
        val dataDensityScore = when {
            dataPoints >= 1000 -> 1.0
            dataPoints >= 500 -> 0.9
            dataPoints >= 100 -> 0.7
            else -> 0.5
        }
        return iriScore * dataDensityScore
    }
}