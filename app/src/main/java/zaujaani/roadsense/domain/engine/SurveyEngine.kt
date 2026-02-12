package zaujaani.roadsense.domain.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import zaujaani.roadsense.core.events.RealtimeRoadsenseBus
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Survey Engine - Core logic untuk mengelola survey lifecycle
 *
 * Bertanggung jawab untuk:
 * - Menyimpan state survey (Running, Paused, Stopped, Idle)
 * - Generate session ID untuk setiap survey
 * - Menyimpan survey ID dari database
 *
 * ⚠️ TIDAK lagi melakukan parsing data ESP32.
 *    Parsing dilakukan di BluetoothGateway + ESP32SensorData.
 *    Penyimpanan telemetry dilakukan oleh TrackingForegroundService.
 */
@Singleton
class SurveyEngine @Inject constructor(
    private val bus: RealtimeRoadsenseBus   // ✅ inject bus
) {

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
        val state = SurveyState.Running(sessionId, surveyId)
        _surveyState.value = state
        bus.publishSurveyState(state)   // ✅ publish
        Timber.d("▶️ Survey started: sessionId=$sessionId, surveyId=$surveyId")
    }

    /**
     * Pause survey
     */
    fun pauseSurvey() {
        val current = _surveyState.value
        if (current is SurveyState.Running) {
            val state = SurveyState.Paused(current.sessionId, current.surveyId)
            _surveyState.value = state
            bus.publishSurveyState(state)   // ✅ publish
            Timber.d("⏸️ Survey paused")
        }
    }

    /**
     * Resume survey
     */
    fun resumeSurvey() {
        val current = _surveyState.value
        if (current is SurveyState.Paused) {
            val state = SurveyState.Running(current.sessionId, current.surveyId)
            _surveyState.value = state
            bus.publishSurveyState(state)   // ✅ publish
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
                val state = SurveyState.Stopped(current.sessionId, current.surveyId)
                _surveyState.value = state
                bus.publishSurveyState(state)   // ✅ publish
                Timber.d("⏹️ Survey stopped")
            }
            is SurveyState.Paused -> {
                val state = SurveyState.Stopped(current.sessionId, current.surveyId)
                _surveyState.value = state
                bus.publishSurveyState(state)   // ✅ publish
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
        bus.publishSurveyState(SurveyState.Idle)   // ✅ publish
        _currentSessionId.value = null
        currentSurveyId = -1
        Timber.d("🔄 Survey engine reset")
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