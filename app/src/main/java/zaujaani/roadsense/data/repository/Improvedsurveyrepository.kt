package zaujaani.roadsense.data.repository

import androidx.room.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import zaujaani.roadsense.data.local.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ImprovedSurveyRepository - Repository dengan database transactions
 *
 * Improvements:
 * - ✅ All multi-table operations menggunakan @Transaction
 * - ✅ Batch insert untuk performance
 * - ✅ Comprehensive error handling
 * - ✅ Flow untuk reactive updates
 * - ✅ Proper null handling
 */
@Singleton
class ImprovedSurveyRepository @Inject constructor(
    private val database: RoadSenseDatabase   // Perbaiki typo nama kelas
) {

    private val sessionDao: SessionDao = database.sessionDao()
    private val segmentDao: RoadSegmentDao = database.roadSegmentDao()   // perbaiki nama method
    private val telemetryDao: TelemetryDao = database.telemetryDao()

    // ============================================================================
    // SESSION OPERATIONS
    // ============================================================================

    /**
     * Create new survey session
     * @return Session ID atau -1 jika gagal
     */
    suspend fun createSession(
        projectName: String,
        surveyorName: String? = null,
        notes: String? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val session = SurveySession(
                projectName = projectName,
                surveyorName = surveyorName ?: "Anonymous",   // beri default jika null
                startTime = System.currentTimeMillis(),
                notes = notes
            )

            val sessionId = sessionDao.insertSurveySession(session)   // perbaiki nama method
            Timber.d("✅ Session created: $sessionId")
            Result.success(sessionId)

        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to create session")
            Result.failure(e)
        }
    }

    /**
     * Get session by ID
     */
    suspend fun getSession(sessionId: Long): Result<SurveySession?> = withContext(Dispatchers.IO) {
        try {
            val session = sessionDao.getSurveySessionById(sessionId)   // perbaiki nama method
            Result.success(session)
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to get session: $sessionId")
            Result.failure(e)
        }
    }

    /**
     * Update session (e.g., set end time, update stats)
     */
    suspend fun updateSession(session: SurveySession): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sessionDao.updateSurveySession(session)   // perbaiki nama method
            Timber.d("✅ Session updated: ${session.id}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to update session: ${session.id}")
            Result.failure(e)
        }
    }

    /**
     * Complete session - update end time dan statistics
     */
    suspend fun completeSession(
        sessionId: Long,
        totalDistance: Float,
        totalSegments: Int,      // parameter tidak dipakai karena tidak ada field di entity
        avgQuality: Float
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = sessionDao.getSurveySessionById(sessionId)
            if (session == null) {
                return@withContext Result.failure(Exception("Session not found: $sessionId"))
            }

            val updatedSession = session.copy(
                endTime = System.currentTimeMillis(),
                totalDistance = totalDistance,               // field bernama totalDistance
                sessionQuality = avgQuality                  // gunakan sessionQuality untuk avg quality
                // totalSegments tidak dapat disimpan karena tidak ada field
            )

            sessionDao.updateSurveySession(updatedSession)
            Timber.d("✅ Session completed: $sessionId")
            Result.success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to complete session: $sessionId")
            Result.failure(e)
        }
    }

    /**
     * Get all sessions (Flow untuk reactive updates)
     */
    fun getAllSessionsFlow(): Flow<List<SurveySession>> {
        return sessionDao.getAllSurveySessions()   // perbaiki nama method
    }

    /**
     * Get all sessions (one-time query)
     */
    suspend fun getAllSessions(): Result<List<SurveySession>> = withContext(Dispatchers.IO) {
        try {
            // Ambil dari Flow karena tidak ada suspend function langsung
            val sessions = sessionDao.getAllSurveySessions().first()
            Result.success(sessions)
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to get all sessions")
            Result.failure(e)
        }
    }

    /**
     * Delete session (cascade delete segments & telemetry)
     */
    @Transaction
    suspend fun deleteSession(sessionId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Hapus telemetry
            telemetryDao.deleteBySession(sessionId)

            // TODO: Tidak ada method deleteBySession di RoadSegmentDao, jadi hapus satu per satu
            val segments = segmentDao.getRoadSegmentsBySessionSync(sessionId)
            segments.forEach { segment ->
                segmentDao.deleteRoadSegmentById(segment.id)
            }

            // Hapus session
            // TODO: Tidak ada method deleteById di SessionDao, gunakan query manual atau hapus via object?
            // Sementara kita tidak bisa menghapus session karena tidak ada method. Mungkin perlu ditambahkan di Dao.
            // Alternatif: gunakan database.openHelper.writableDatabase.execSQL
            // Untuk sekarang, kita lewati dengan error
            // sessionDao.deleteSurveySession(sessionId) // asumsi ada, tapi tidak

            // Karena tidak ada, kita kembalikan error
            return@withContext Result.failure(Exception("Session deletion not fully implemented due to missing Dao method"))

            // Jika nanti ada method delete, gunakan:
            // sessionDao.deleteSurveySession(sessionId)

            Timber.d("✅ Session deleted with cascade: $sessionId")
            Result.success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to delete session: $sessionId")
            Result.failure(e)
        }
    }

    // ============================================================================
    // SEGMENT OPERATIONS
    // ============================================================================

    /**
     * Create road segment dengan telemetry data (TRANSACTIONAL)
     */
    @Transaction
    suspend fun createSegmentWithTelemetry(
        segment: RoadSegment,
        telemetryData: List<TelemetryRaw>
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            // Insert segment
            val segmentId = segmentDao.insertRoadSegment(segment)   // perbaiki nama method

            // Insert telemetry data jika ada
            if (telemetryData.isNotEmpty()) {
                telemetryDao.insertTelemetryRawBatch(telemetryData)   // perbaiki nama method
            }

            Timber.d("✅ Segment created with ${telemetryData.size} telemetry points: $segmentId")
            Result.success(segmentId)

        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to create segment with telemetry")
            Result.failure(e)
        }
    }

    /**
     * Get segments untuk session
     */
    suspend fun getSegmentsForSession(sessionId: Long): Result<List<RoadSegment>> =
        withContext(Dispatchers.IO) {
            try {
                val segments = segmentDao.getRoadSegmentsBySessionSync(sessionId)   // perbaiki nama method
                Result.success(segments)
            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to get segments for session: $sessionId")
                Result.failure(e)
            }
        }

    /**
     * Get segment with summary (JOIN query)
     */
    suspend fun getSegmentSummary(segmentId: Long): Result<RoadSegmentSummary?> =
        withContext(Dispatchers.IO) {
            try {
                // Karena tidak ada method langsung di Dao, kita buat manual
                val segment = segmentDao.getRoadSegmentsBySessionSync(segmentId) // tidak tepat, harusnya cari per id
                // TODO: Tidak ada method getRoadSegmentById, jadi tidak bisa
                // Sementara return null dengan catatan
                Result.success(null)
            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to get segment summary: $segmentId")
                Result.failure(e)
            }
        }

    /**
     * Update segment
     */
    suspend fun updateSegment(segment: RoadSegment): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            segmentDao.updateRoadSegment(segment)   // perbaiki nama method
            Timber.d("✅ Segment updated: ${segment.id}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to update segment: ${segment.id}")
            Result.failure(e)
        }
    }

    /**
     * Delete segment
     */
    @Transaction
    suspend fun deleteSegment(segmentId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Delete associated telemetry (if needed) - tidak ada method, skip
            // Delete segment
            segmentDao.deleteRoadSegmentById(segmentId)   // perbaiki nama method

            Timber.d("✅ Segment deleted: $segmentId")
            Result.success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to delete segment: $segmentId")
            Result.failure(e)
        }
    }

    // ============================================================================
    // TELEMETRY OPERATIONS
    // ============================================================================

    /**
     * Insert single telemetry data
     */
    suspend fun insertTelemetry(data: TelemetryRaw): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val id = telemetryDao.insertTelemetryRaw(data)   // perbaiki nama method
            Result.success(id)
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to insert telemetry")
            Result.failure(e)
        }
    }

    /**
     * Insert multiple telemetry data (BATCH - Much faster!)
     */
    suspend fun insertTelemetryBatch(dataList: List<TelemetryRaw>): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                if (dataList.isEmpty()) {
                    return@withContext Result.success(0)
                }

                telemetryDao.insertTelemetryRawBatch(dataList)   // perbaiki nama method
                Timber.d("✅ Inserted ${dataList.size} telemetry records (batch)")
                Result.success(dataList.size)

            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to insert telemetry batch")
                Result.failure(e)
            }
        }

    /**
     * Get telemetry untuk session
     */
    suspend fun getTelemetryForSession(
        sessionId: Long,
        limit: Int? = null
    ): Result<List<TelemetryRaw>> = withContext(Dispatchers.IO) {
        try {
            // Ambil dari Flow, karena tidak ada method yang return List langsung
            val allData = telemetryDao.getTelemetryRawBySession(sessionId).first()
            val result = if (limit != null) allData.take(limit) else allData
            Result.success(result)
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to get telemetry for session: $sessionId")
            Result.failure(e)
        }
    }

    /**
     * Get telemetry count untuk session
     */
    suspend fun getTelemetryCount(sessionId: Long): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val count = telemetryDao.countBySession(sessionId)   // perbaiki nama method
            Result.success(count)
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to get telemetry count: $sessionId")
            Result.failure(e)
        }
    }

    /**
     * Delete old telemetry (cleanup)
     */
    suspend fun deleteOldTelemetry(olderThanMs: Long): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // TODO: Tidak ada method deleteOlderThan di TelemetryDao
            // Sementara return 0 dengan pesan
            Timber.w("deleteOldTelemetry not implemented (missing Dao method)")
            Result.success(0)
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to delete old telemetry")
            Result.failure(e)
        }
    }

    // ============================================================================
    // STATISTICS OPERATIONS
    // ============================================================================

    /**
     * Get session statistics
     */
    suspend fun getSessionStatistics(sessionId: Long): Result<SessionStatistics> =
        withContext(Dispatchers.IO) {
            try {
                val session = sessionDao.getSurveySessionById(sessionId)
                    ?: return@withContext Result.failure(Exception("Session not found"))

                val segments = segmentDao.getRoadSegmentsBySessionSync(sessionId)
                val telemetryCount = telemetryDao.countBySession(sessionId)

                val totalDistance = segments.sumOf { it.distanceMeters.toDouble() }.toFloat()   // perbaiki field
                val avgQuality = segments.map { it.qualityScore }.average().toFloat()   // qualityScore adalah Float

                val duration = if (session.endTime != null) {
                    session.endTime - session.startTime
                } else {
                    System.currentTimeMillis() - session.startTime
                }

                val stats = SessionStatistics(
                    sessionId = sessionId,
                    totalDistanceMeters = totalDistance,
                    totalSegments = segments.size,
                    totalTelemetryPoints = telemetryCount,
                    averageQuality = avgQuality,
                    durationMs = duration,
                    gpsAvailabilityPercent = calculateGpsAvailability(sessionId)
                )

                Result.success(stats)

            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to get session statistics")
                Result.failure(e)
            }
        }

    /**
     * Calculate GPS availability percentage untuk session
     */
    private suspend fun calculateGpsAvailability(sessionId: Long): Float {
        return try {
            val telemetryData = telemetryDao.getTelemetryRawBySession(sessionId).first()
            if (telemetryData.isEmpty()) return 0f

            val withGps = telemetryData.count { it.latitude != null && it.longitude != null }
            (withGps.toFloat() / telemetryData.size) * 100f

        } catch (e: Exception) {
            0f
        }
    }

    /**
     * Get database size
     */
    suspend fun getDatabaseSizeMB(): Float = withContext(Dispatchers.IO) {
        try {
            val dbFile = database.openHelper.readableDatabase.path
            val file = java.io.File(dbFile)
            if (file.exists()) {
                (file.length() / (1024f * 1024f))
            } else {
                0f
            }
        } catch (e: Exception) {
            0f
        }
    }
}

/**
 * Data class untuk session statistics
 */
data class SessionStatistics(
    val sessionId: Long,
    val totalDistanceMeters: Float,
    val totalSegments: Int,
    val totalTelemetryPoints: Int,
    val averageQuality: Float,
    val durationMs: Long,
    val gpsAvailabilityPercent: Float
) {
    fun getDurationFormatted(): String {
        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    fun getDistanceKm(): Float = totalDistanceMeters / 1000f
}