package zaujaani.roadsense.core.events

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import zaujaani.roadsense.domain.engine.SurveyEngine
import zaujaani.roadsense.domain.model.ESP32SensorData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealtimeRoadsenseBus @Inject constructor() {

    private val _sensorData = MutableStateFlow<ESP32SensorData?>(null)
    val sensorData: StateFlow<ESP32SensorData?> = _sensorData.asStateFlow()

    private val _gpsLocation = MutableStateFlow<Location?>(null)
    val gpsLocation: StateFlow<Location?> = _gpsLocation.asStateFlow()

    private val _surveyState = MutableStateFlow<SurveyEngine.SurveyState>(SurveyEngine.SurveyState.Idle)
    val surveyState: StateFlow<SurveyEngine.SurveyState> = _surveyState.asStateFlow()

    fun publishSensorData(data: ESP32SensorData) {
        _sensorData.value = data
    }

    fun publishGpsLocation(location: Location?) {
        _gpsLocation.value = location
    }

    fun publishSurveyState(state: SurveyEngine.SurveyState) {
        _surveyState.value = state
    }

    fun reset() {
        _sensorData.value = null
        _gpsLocation.value = null
        _surveyState.value = SurveyEngine.SurveyState.Idle
    }
}