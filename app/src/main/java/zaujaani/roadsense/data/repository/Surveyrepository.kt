package zaujaani.roadsense.data.repository

import kotlinx.coroutines.flow.Flow
import zaujaani.roadsense.data.local.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SurveyRepository @Inject constructor(
    // ============ SESSION & SEGMENT ============
    private val sessionDao: SessionDao,
    private val roadSegmentDao: RoadSegmentDao,
    // ============ CALIBRATION ============
    private val calibrationDao: CalibrationDao
) {

    // ============ CALIBRATION (TETAP) ============
    suspend fun hasCalibration(): Boolean = calibrationDao.getActiveCalibration() != null

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

    // ============ SURVEY SESSION (AKTIF) ============
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

    // ============ ROAD SEGMENT (AKTIF) ============
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


}