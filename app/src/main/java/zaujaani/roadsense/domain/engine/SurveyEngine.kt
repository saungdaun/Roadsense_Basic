package zaujaani.roadsense.domain.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
 * - Memberi sinyal **ready** setelah inisialisasi selesai (fix race condition)
 *
 * ⚠️ TIDAK lagi melakukan parsing data ESP32.
 *    Parsing dilakukan di BluetoothGateway + ESP32SensorData.
 *    Penyimpanan telemetry dilakukan oleh TrackingForegroundService.
 */
@Singleton
class SurveyEngine @Inject constructor(
    private val bus: RealtimeRoadsenseBus
) {

    private val _surveyState = MutableStateFlow<SurveyState>(SurveyState.Idle)
    val surveyState: StateFlow<SurveyState> = _surveyState.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private var currentSurveyId: Long = -1

    // ========== READINESS SIGNAL ==========
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    sealed class SurveyState {
        object Idle : SurveyState()
        data class Running(val sessionId: String, val surveyId: Long) : SurveyState()
        data class Paused(val sessionId: String, val surveyId: Long) : SurveyState()
        data class Stopped(val sessionId: String, val surveyId: Long) : SurveyState()
    }

    /**
     * Start new survey (SUSPEND)
     * - Reset readiness signal
     * - Lakukan inisialisasi async
     * - Set state & publish
     * - Set ready = true setelah selesai
     */
    suspend fun startSurvey(sessionId: String, surveyId: Long) {
        // 🔴 RESET readiness TERLEBIH DAHULU – krusial!
        _isReady.value = false

        // Simulasi / real initialization (misal: buka koneksi, alokasi buffer)
        withContext(Dispatchers.IO) {
            // Di sini bisa ditambahkan kode inisialisasi sesungguhnya.
            Timber.d("🔄 SurveyEngine: initializing...")
        }

        _currentSessionId.value = sessionId
        currentSurveyId = surveyId
        val state = SurveyState.Running(sessionId, surveyId)
        _surveyState.value = state
        bus.publishSurveyState(state)

        // ✅ Engine siap menerima data
        _isReady.value = true
        Timber.d("▶️ Survey started & ready: sessionId=$sessionId, surveyId=$surveyId")
    }

    /**
     * Pause survey
     */
    fun pauseSurvey() {
        val current = _surveyState.value
        if (current is SurveyState.Running) {
            val state = SurveyState.Paused(current.sessionId, current.surveyId)
            _surveyState.value = state
            bus.publishSurveyState(state)
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
            bus.publishSurveyState(state)
            Timber.d("▶️ Survey resumed")
        }
    }

    /**
     * Stop survey – reset readiness signal
     */
    fun stopSurvey() {
        val current = _surveyState.value
        when (current) {
            is SurveyState.Running -> {
                val state = SurveyState.Stopped(current.sessionId, current.surveyId)
                _surveyState.value = state
                bus.publishSurveyState(state)
                _isReady.value = false
                Timber.d("⏹️ Survey stopped")
            }
            is SurveyState.Paused -> {
                val state = SurveyState.Stopped(current.sessionId, current.surveyId)
                _surveyState.value = state
                bus.publishSurveyState(state)
                _isReady.value = false
                Timber.d("⏹️ Survey stopped (from paused)")
            }
            else -> {
                Timber.w("⚠️ Cannot stop survey from state: $current")
            }
        }
    }

    /**
     * Reset engine – reset readiness signal
     */
    fun reset() {
        _surveyState.value = SurveyState.Idle
        bus.publishSurveyState(SurveyState.Idle)
        _currentSessionId.value = null
        currentSurveyId = -1
        _isReady.value = false
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
    fun getCurrentSurveyId(): Long = currentSurveyId

    /**
     * Check if survey is running
     */
    fun isRunning(): Boolean = _surveyState.value is SurveyState.Running

    /**
     * Check if survey is paused
     */
    fun isPaused(): Boolean = _surveyState.value is SurveyState.Paused
}