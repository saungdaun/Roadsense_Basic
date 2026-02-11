package zaujaani.roadsense.features.calibration

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsense.data.local.DeviceCalibration
import zaujaani.roadsense.data.repository.SurveyRepository
import javax.inject.Inject

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val repository: SurveyRepository
) : ViewModel() {

    private val _calibrationState = MutableLiveData<CalibrationState>(CalibrationState.Loading)
    val calibrationState: LiveData<CalibrationState> = _calibrationState

    private var currentCalibrationId: Long? = null

    sealed class CalibrationState {
        object Loading : CalibrationState()
        data class Loaded(val calibration: DeviceCalibration?) : CalibrationState()
        data class Error(val message: String) : CalibrationState()
        object Saved : CalibrationState()
    }

    fun loadCalibration() {
        viewModelScope.launch {
            _calibrationState.value = CalibrationState.Loading
            try {
                val calibration = repository.getActiveCalibration()
                currentCalibrationId = calibration?.id
                _calibrationState.value = CalibrationState.Loaded(calibration)
                Timber.d("Loaded calibration: $calibration")
            } catch (e: Exception) {
                _calibrationState.value = CalibrationState.Error("Gagal memuat kalibrasi: ${e.message}")
                Timber.e(e, "Failed to load calibration")
            }
        }
    }

    fun saveCalibration(
        deviceName: String,
        wheelDiameter: Float,
        wheelDiameterUnit: String,
        pulsesPerRotation: Int,
        vehicleType: String? = null,
        tirePressure: Float? = null,
        loadWeight: Float? = null,
        notes: String? = null
    ) {
        viewModelScope.launch {
            _calibrationState.value = CalibrationState.Loading
            try {
                val calibration = DeviceCalibration(
                    id = currentCalibrationId ?: 0L,
                    deviceName = deviceName,
                    wheelDiameter = wheelDiameter,
                    wheelDiameterUnit = wheelDiameterUnit,
                    pulsesPerRotation = pulsesPerRotation,
                    vehicleType = vehicleType,
                    tirePressure = tirePressure,
                    loadWeight = loadWeight,
                    notes = notes,
                    isActive = true
                )

                repository.saveCalibration(calibration)
                _calibrationState.value = CalibrationState.Saved
                Timber.d("Calibration saved successfully")
            } catch (e: Exception) {
                _calibrationState.value = CalibrationState.Error("Gagal menyimpan kalibrasi: ${e.message}")
                Timber.e(e, "Failed to save calibration")
            }
        }
    }

    fun calculateCircumference(diameter: Float, unit: String): Float {
        return diameter * Math.PI.toFloat()
    }

    fun calculateDistancePerPulse(diameter: Float, unit: String, pulsesPerRotation: Int): Float {
        val circumference = calculateCircumference(diameter, unit)
        return circumference / pulsesPerRotation
    }

    fun getCalibrationSummary(
        diameter: Float,
        unit: String,
        pulsesPerRotation: Int
    ): Map<String, String> {
        val circumference = calculateCircumference(diameter, unit)
        val distancePerPulse = circumference / pulsesPerRotation

        return mapOf(
            "diameter" to "$diameter $unit",
            "circumference" to String.format("%.2f %s", circumference, unit),
            "pulses_per_rotation" to pulsesPerRotation.toString(),
            "distance_per_pulse" to String.format("%.4f %s", distancePerPulse, unit),
            "distance_per_1000_pulses" to String.format("%.2f %s", distancePerPulse * 1000, unit)
        )
    }
}