package zaujaani.roadsense.data.repository

import kotlinx.coroutines.flow.Flow
import zaujaani.roadsense.data.local.TelemetryDao
import zaujaani.roadsense.data.local.TelemetryRaw
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelemetryRepository @Inject constructor(
    private val telemetryDao: TelemetryDao
) {

    // ========== INSERT ==========
    suspend fun insertTelemetryRaw(telemetry: TelemetryRaw): Long =
        telemetryDao.insertTelemetryRaw(telemetry)

    suspend fun insertTelemetryRawBatch(telemetryList: List<TelemetryRaw>) =
        telemetryDao.insertTelemetryRawBatch(telemetryList)

    // ========== QUERY ==========
    fun getTelemetryRawBySession(sessionId: Long): Flow<List<TelemetryRaw>> =
        telemetryDao.getTelemetryRawBySession(sessionId)

    suspend fun getLatestTelemetryRaw(sessionId: Long): TelemetryRaw? =
        telemetryDao.getLatestTelemetryRaw(sessionId)

    suspend fun countBySession(sessionId: Long): Int =
        telemetryDao.countBySession(sessionId)

    suspend fun deleteBySession(sessionId: Long) =
        telemetryDao.deleteBySession(sessionId)


}