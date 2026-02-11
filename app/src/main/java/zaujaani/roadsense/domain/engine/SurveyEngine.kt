package zaujaani.roadsense.domain.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import zaujaani.roadsense.data.local.TelemetryEntity
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Survey Engine - Core logic untuk mengelola survey lifecycle
 *
 * Bertanggung jawab untuk:
 * - Parse data dari ESP32
 * - Validate CRC
 * - Generate telemetry entity
 * - Track survey state
 */
@Singleton
class SurveyEngine @Inject constructor() {

    private val _surveyState = MutableStateFlow<SurveyState>(SurveyState.Idle)
    val surveyState: StateFlow<SurveyState> = _surveyState.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private var currentSurveyId: Long = -1

    sealed class SurveyState {
        object Idle : SurveyState()
        data class Running(val sessionId: String, val surveyId: Long) : SurveyState()
        data class Paused(val sessionId: String, val surveyId: Long) : SurveyState()
        data class Stopped(val sessionId: String, val surveyId: Long) : SurveyState()
    }

    /**
     * Start new survey
     */
    fun startSurvey(sessionId: String, surveyId: Long) {
        _currentSessionId.value = sessionId
        currentSurveyId = surveyId
        _surveyState.value = SurveyState.Running(sessionId, surveyId)
        Timber.d("▶️ Survey started: sessionId=$sessionId, surveyId=$surveyId")
    }

    /**
     * Pause survey
     */
    fun pauseSurvey() {
        val current = _surveyState.value
        if (current is SurveyState.Running) {
            _surveyState.value = SurveyState.Paused(current.sessionId, current.surveyId)
            Timber.d("⏸️ Survey paused")
        }
    }

    /**
     * Resume survey
     */
    fun resumeSurvey() {
        val current = _surveyState.value
        if (current is SurveyState.Paused) {
            _surveyState.value = SurveyState.Running(current.sessionId, current.surveyId)
            Timber.d("▶️ Survey resumed")
        }
    }

    /**
     * Stop survey
     */
    fun stopSurvey() {
        val current = _surveyState.value
        when (current) {
            is SurveyState.Running -> {
                _surveyState.value = SurveyState.Stopped(current.sessionId, current.surveyId)
                Timber.d("⏹️ Survey stopped")
            }
            is SurveyState.Paused -> {
                _surveyState.value = SurveyState.Stopped(current.sessionId, current.surveyId)
                Timber.d("⏹️ Survey stopped (from paused)")
            }
            else -> {
                Timber.w("⚠️ Cannot stop survey from state: $current")
            }
        }
    }

    /**
     * Reset engine
     */
    fun reset() {
        _surveyState.value = SurveyState.Idle
        _currentSessionId.value = null
        currentSurveyId = -1
        Timber.d("🔄 Survey engine reset")
    }

    /**
     * Parse incoming data from ESP32
     *
     * Format: DATA|sessionId|timestamp|distance|speed|accelZ|iri|lat|lon|alt|gps_speed|gps_acc|bat|state|crc
     *
     * Example:
     * DATA|20250211-143022|1707658222000|1234.56|45.2|-0.15|3.5|37.7749|-122.4194|10.5|44.8|5.0|3.85|RUNNING|A3F2
     */
    fun parseIncomingData(data: String): TelemetryEntity? {
        try {
            val parts = data.split("|")

            // Validate format
            if (parts.size < 15 || parts[0] != "DATA") {
                Timber.w("⚠️ Invalid data format: $data")
                return null
            }

            // Validate CRC
            val receivedCRC = parts[14]
            val calculatedCRC = calculateCRC(data.substringBeforeLast("|"))

            if (receivedCRC != calculatedCRC) {
                Timber.w("⚠️ CRC mismatch. Received: $receivedCRC, Calculated: $calculatedCRC")
                return null
            }

            // Parse fields
            val sessionId = parts[1]
            val timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis()
            val distance = parts[3].toDoubleOrNull() ?: 0.0
            val speed = parts[4].toDoubleOrNull() ?: 0.0
            val accelZ = parts[5].toDoubleOrNull() ?: 0.0
            val iri = parts[6].toDoubleOrNull() ?: 0.0
            val lat = parts[7].toDoubleOrNull()
            val lon = parts[8].toDoubleOrNull()
            val alt = parts[9].toDoubleOrNull()
            val gpsSpeed = parts[10].toDoubleOrNull()
            val gpsAcc = parts[11].toFloatOrNull()
            val battery = parts[12].toDoubleOrNull()
            val state = parts[13]

            // Determine GPS availability
            val gpsAvailable = lat != null && lon != null

            // Determine quality flag
            val qualityFlag = when {
                iri < 2.0 -> "EXCELLENT"
                iri < 4.0 -> "GOOD"
                iri < 6.0 -> "FAIR"
                iri < 8.0 -> "POOR"
                else -> "BAD"
            }

            // Create telemetry entity
            return TelemetryEntity(
                surveyId = currentSurveyId,
                timestamp = timestamp,
                distanceFromEncoder = distance,
                speedFromEncoder = speed,
                chainage = distance, // Chainage sama dengan total distance
                accelZ = accelZ,
                accelZFiltered = accelZ, // Already filtered by ESP32
                iri = iri,
                qualityFlag = qualityFlag,
                latitude = lat,
                longitude = lon,
                altitude = alt,
                gpsSpeed = gpsSpeed,
                gpsAccuracy = gpsAcc,
                gpsAvailable = gpsAvailable,
                batteryVoltage = battery,
                systemState = state,
                dataSource = "SENSOR",
                validationFlag = true
            )

        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing data: $data")
            return null
        }
    }

    /**
     * Calculate simple CRC for data validation
     */
    private fun calculateCRC(data: String): String {
        var crc = 0
        for (char in data) {
            crc = (crc xor char.code) and 0xFFFF
        }
        return crc.toString(16).uppercase().padStart(4, '0')
    }

    /**
     * Generate session ID
     * Format: YYYYMMDD-HHMMSS
     */
    fun generateSessionId(): String {
        val sdf = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
        return sdf.format(Date())
    }

    /**
     * Get current survey ID
     */
    fun getCurrentSurveyId(): Long {
        return currentSurveyId
    }

    /**
     * Check if survey is running
     */
    fun isRunning(): Boolean {
        return _surveyState.value is SurveyState.Running
    }

    /**
     * Check if survey is paused
     */
    fun isPaused(): Boolean {
        return _surveyState.value is SurveyState.Paused
    }
}