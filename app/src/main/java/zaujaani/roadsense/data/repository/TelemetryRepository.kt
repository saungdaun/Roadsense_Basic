package zaujaani.roadsense.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import zaujaani.roadsense.data.local.TelemetryDao
import zaujaani.roadsense.data.local.TelemetryRaw
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelemetryRepository @Inject constructor(
    private val telemetryDao: TelemetryDao
) {

    private val _latestTelemetry = MutableStateFlow<TelemetryRaw?>(null)
    val latestTelemetry = _latestTelemetry.asStateFlow()

    suspend fun insertTelemetryRaw(data: TelemetryRaw) {
        telemetryDao.insertTelemetry(data)   // ✅ nama method di DAO = insertTelemetry
    }

    fun updateLatestTelemetry(data: TelemetryRaw) {
        _latestTelemetry.value = data
    }

    fun getTelemetryRawBySession(sessionId: Long): Flow<List<TelemetryRaw>> =
        telemetryDao.getTelemetryBySession(sessionId)

    suspend fun getTelemetryCount(sessionId: Long): Int =
        telemetryDao.getTelemetryCount(sessionId)

    suspend fun getLatestTelemetryPoint(sessionId: Long): TelemetryRaw? =
        telemetryDao.getLatestTelemetry(sessionId)   // ✅ method baru di DAO

    suspend fun insertTelemetryBatch(telemetryList: List<TelemetryRaw>) {
        telemetryDao.insertBatchTelemetry(telemetryList)  // ✅ nama method di DAO = insertBatchTelemetry
    }
}