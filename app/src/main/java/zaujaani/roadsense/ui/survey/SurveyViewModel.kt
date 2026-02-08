package zaujaani.roadsense.ui.survey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import zaujaani.roadsense.data.models.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SurveyViewModel : ViewModel() {

    // State Flows untuk data real-time
    private val _surveyState = MutableStateFlow(SurveyState.IDLE)
    val surveyState: StateFlow<SurveyState> = _surveyState

    private val _bluetoothState = MutableStateFlow(BluetoothState.DISCONNECTED)
    val bluetoothState: StateFlow<BluetoothState> = _bluetoothState

    private val _gpsState = MutableStateFlow(GpsState.DISABLED)
    val gpsState: StateFlow<GpsState> = _gpsState

    private val _surveyDisplayData = MutableStateFlow(SurveyDisplayData())
    val surveyDisplayData: StateFlow<SurveyDisplayData> = _surveyDisplayData

    private val _sessionDisplayData = MutableStateFlow(SessionDisplayData())
    val sessionDisplayData: StateFlow<SessionDisplayData> = _sessionDisplayData

    // Data yang dipilih user
    private val _selectedSurface = MutableStateFlow<SurfaceType?>(null)
    val selectedSurface: StateFlow<SurfaceType?> = _selectedSurface

    private val _selectedCondition = MutableStateFlow<RoadCondition?>(null)
    val selectedCondition: StateFlow<RoadCondition?> = _selectedCondition

    // Data session
    private var currentSessionId: String = "#000001"

    init {
        // Inisialisasi data default
        resetSessionData()
    }

    // ==================== PUBLIC FUNCTIONS ====================

    fun startSurvey() {
        if (_surveyState.value != SurveyState.RUNNING) {
            _surveyState.value = SurveyState.RUNNING
            updateSessionData(status = SurveyState.RUNNING)
            // TODO: Panggil BluetoothGateway untuk mulai survey
        }
    }

    fun pauseSurvey() {
        if (_surveyState.value == SurveyState.RUNNING) {
            _surveyState.value = SurveyState.PAUSED
            updateSessionData(status = SurveyState.PAUSED)
            // TODO: Panggil BluetoothGateway untuk pause survey
        }
    }

    fun stopSurvey() {
        _surveyState.value = SurveyState.STOPPED
        updateSessionData(status = SurveyState.STOPPED)
        // TODO: Panggil BluetoothGateway untuk stop survey
    }

    fun selectSurface(surface: SurfaceType) {
        _selectedSurface.value = surface
        updateSessionData(activeSurface = surface)
    }

    fun selectCondition(condition: RoadCondition) {
        _selectedCondition.value = condition
        updateSessionData(activeCondition = condition)
    }

    fun resetSession() {
        currentSessionId = generateNewSessionId()
        resetSessionData()
        // TODO: Reset data sensor dan trip
    }

    // ==================== PRIVATE FUNCTIONS ====================

    private fun updateSessionData(
        status: SurveyState? = null,
        activeSurface: SurfaceType? = null,
        activeCondition: RoadCondition? = null
    ) {
        val current = _sessionDisplayData.value
        _sessionDisplayData.value = current.copy(
            status = status ?: current.status,
            activeSurface = activeSurface ?: current.activeSurface,
            activeCondition = activeCondition ?: current.activeCondition,
            sessionId = currentSessionId
        )
    }

    private fun resetSessionData() {
        _sessionDisplayData.value = SessionDisplayData(
            sessionId = currentSessionId,
            status = SurveyState.IDLE,
            activeSurface = null,
            activeCondition = null
        )

        _surveyDisplayData.value = SurveyDisplayData()
        _selectedSurface.value = null
        _selectedCondition.value = null
    }

    private fun generateNewSessionId(): String {
        // Generate simple session ID (nanti bisa diganti dengan UUID atau timestamp)
        val randomNum = (100000..999999).random()
        return "#${randomNum}"
    }

    // ==================== UPDATE FUNCTIONS (dipanggil dari EventBus) ====================

    fun updateBluetoothState(state: BluetoothState) {
        _bluetoothState.value = state
    }

    fun updateGpsState(state: GpsState) {
        _gpsState.value = state
    }

    fun updateSurveyData(data: SurveyDisplayData) {
        _surveyDisplayData.value = data
    }

    // Fungsi untuk update data sensor dari Bluetooth (nanti diintegrasikan dengan EventBus)
    fun updateSensorData(
        tripDistance: Float,
        speed: Float,
        accelerationZ: Float,
        elapsedTime: String
    ) {
        val current = _surveyDisplayData.value
        _surveyDisplayData.value = current.copy(
            tripDistance = String.format("%.1f m", tripDistance),
            currentSpeed = String.format("%.1f km/h", speed),
            accelerationZ = String.format("%.2f g", accelerationZ),
            elapsedTime = elapsedTime
        )
    }
}