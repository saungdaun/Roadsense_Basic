package zaujaani.roadsense.data.repository

import androidx.room.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import zaujaani.roadsense.data.local.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ImprovedSurveyRepository – Repository untuk Survey Session dan Road Segment
 *
 * ✅ Menggunakan Result untuk error handling
 * ✅ Transaction untuk operasi yang melibatkan banyak tabel
 * ✅ Flow untuk reactive data
 * ✅ Hanya fokus pada session & segment (telemetry dipisah ke TelemetryRepository)
 */
@Singleton
class ImprovedSurveyRepository @Inject constructor(
    database: RoadSenseDatabase
) {
    private val sessionDao = database.sessionDao()
    private val segmentDao = database.roadSegmentDao()
    private val calibrationDao = database.calibrationDao() // opsional, bisa dipindah

    // ============================================================================
    // SESSION OPERATIONS
    // ============================================================================

    /**
     * Membuat sesi survey baru
     */
    suspend fun createSession(
        projectName: String,
        surveyorName: String = "Anonymous",
        notes: String? = null,
        vehicleType: String? = null,
        deviceName: String? = null,
        calibrationId: Long? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val session = SurveySession(
                projectName = projectName,
                surveyorName = surveyorName,
                startTime = System.currentTimeMillis(),
                notes = notes,
                vehicleType = vehicleType,
                deviceName = deviceName,
                calibrationId = calibrationId,
                isActive = true
            )
            val id = sessionDao.insertSurveySession(session)
            Timber.d("✅ Session created: $id")
            Result.success(id)
        } catch (e: Exception) {
            Timber.e(e, "❌ Gagal membuat session")
            Result.failure(e)
        }


    }
    suspend fun getSummaryBySession(sessionId: Long): List<RoadSegmentSummary> = withContext(Dispatchers.IO) {
        val segments = getSegmentsForSession(sessionId).getOrElse { emptyList() }
        val session = getSession(sessionId).getOrNull() ?: return@withContext emptyList()
        segments.map { RoadSegmentSummary(it, session) }
    }

    // ===== DELETE ROAD SEGMENT (throw exception) =====
    suspend fun deleteRoadSegment(segmentId: Long) {
        deleteSegment(segmentId).getOrThrow()
    }


    /**
     * Mendapatkan session berdasarkan ID
     */
    suspend fun getSession(sessionId: Long): Result<SurveySession?> = withContext(Dispatchers.IO) {
        try {
            Result.success(sessionDao.getSurveySessionById(sessionId))
        } catch (e: Exception) {
            Timber.e(e, "❌ Gagal mengambil session $sessionId")
            Result.failure(e)
        }

    }

    /**
     * Update data session
     */
    suspend fun updateSession(session: SurveySession): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sessionDao.updateSurveySession(session)
            Timber.d("✅ Session updated: ${session.id}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "❌ Gagal update session ${session.id}")
            Result.failure(e)
        }
    }

    /**
     * Menyelesaikan session (set endTime, isActive = false)
     */
    suspend fun completeSession(
        sessionId: Long,
        totalDistance: Float,
        avgQuality: Float
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = sessionDao.getSurveySessionById(sessionId)
                ?: return@withContext Result.failure(Exception("Session not found: $sessionId"))

            val updated = session.copy(
                endTime = System.currentTimeMillis(),
                totalDistance = totalDistance,
                sessionQuality = avgQuality,
                isActive = false
            )
            sessionDao.updateSurveySession(updated)
            Timber.d("✅ Session completed: $sessionId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "❌ Gagal complete session $sessionId")
            Result.failure(e)
        }
    }

    /**
     * Mendapatkan semua session (reactive Flow)
     */
    fun getAllSessions(): Flow<List<SurveySession>> = sessionDao.getAllSurveySessions()

    /**
     * Mendapatkan session yang sedang aktif
     */
    suspend fun getActiveSession(): SurveySession? = sessionDao.getActiveSession()

    /**
     * Menghapus session beserta semua segmen terkait (CASCADE dari DB)
     */
    @Transaction
    suspend fun deleteSession(sessionId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sessionDao.deleteSession(sessionId)
            Timber.d("✅ Session deleted: $sessionId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "❌ Gagal delete session $sessionId")
            Result.failure(e)
        }
    }

    // ============================================================================
    // SEGMENT OPERATIONS
    // ============================================================================

    /**
     * Menyimpan segmen jalan baru
     */
    suspend fun insertSegment(segment: RoadSegment): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val id = segmentDao.insertRoadSegment(segment)
            Timber.d("✅ Segment inserted: $id")
            Result.success(id)
        } catch (e: Exception) {
            Timber.e(e, "❌ Gagal insert segment")
            Result.failure(e)
        }
    }

    /**
     * Mendapatkan semua segmen untuk session tertentu (suspend, one-time)
     */
    suspend fun getSegmentsForSession(sessionId: Long): Result<List<RoadSegment>> = withContext(Dispatchers.IO) {
        try {
            val segments = segmentDao.getRoadSegmentsBySessionSync(sessionId)
            Result.success(segments)
        } catch (e: Exception) {
            Timber.e(e, "❌ Gagal get segments for session $sessionId")
            Result.failure(e)
        }
    }

    /**
     * Flow segmen untuk session tertentu (reactive)
     */
    fun getSegmentsForSessionFlow(sessionId: Long): Flow<List<RoadSegment>> =
        segmentDao.getRoadSegmentsBySession(sessionId)

    /**
     * Update segmen
     */
    suspend fun updateSegment(segment: RoadSegment): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            segmentDao.updateRoadSegment(segment)
            Timber.d("✅ Segment updated: ${segment.id}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "❌ Gagal update segment ${segment.id}")
            Result.failure(e)
        }
    }

    /**
     * Hapus segmen berdasarkan ID
     */
    suspend fun deleteSegment(segmentId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            segmentDao.deleteRoadSegmentById(segmentId)
            Timber.d("✅ Segment deleted: $segmentId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "❌ Gagal delete segment $segmentId")
            Result.failure(e)
        }
    }

    // ============================================================================
    // CALIBRATION OPERATIONS (opsional, bisa dipisah)
    // ============================================================================
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

    // ============================================================================
    // STATISTICS
    // ============================================================================

    /**
     * Mendapatkan statistik sesi berdasarkan data session dan segmen
     */
    suspend fun getSessionStatistics(sessionId: Long): Result<SessionStatistics> = withContext(Dispatchers.IO) {
        try {
            val session = sessionDao.getSurveySessionById(sessionId)
                ?: return@withContext Result.failure(Exception("Session not found"))

            val segments = segmentDao.getRoadSegmentsBySessionSync(sessionId)

            val totalDistance = segments.sumOf { it.distanceMeters.toDouble() }.toFloat()
            val avgQuality = if (segments.isNotEmpty()) {
                segments.map { it.qualityScore }.average().toFloat()
            } else 0f
            val duration = (session.endTime ?: System.currentTimeMillis()) - session.startTime

            val stats = SessionStatistics(
                sessionId = sessionId,
                totalDistanceMeters = totalDistance,
                totalSegments = segments.size,
                averageQuality = avgQuality,
                durationMs = duration
            )
            Result.success(stats)
        } catch (e: Exception) {
            Timber.e(e, "❌ Gagal get statistics for session $sessionId")
            Result.failure(e)
        }
    }
}

/**
 * Data class untuk statistik sesi
 */
data class SessionStatistics(
    val sessionId: Long,
    val totalDistanceMeters: Float,
    val totalSegments: Int,
    val averageQuality: Float,
    val durationMs: Long
) {
    val durationFormatted: String
        get() {
            val seconds = durationMs / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            return when {
                hours > 0 -> "${hours}h ${minutes % 60}m"
                minutes > 0 -> "${minutes}m ${seconds % 60}s"
                else -> "${seconds}s"
            }
        }

    val distanceKm: Float get() = totalDistanceMeters / 1000f
}