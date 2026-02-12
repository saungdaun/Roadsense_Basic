package zaujaani.roadsense.features.calibration

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsense.core.bluetooth.BluetoothGateway
import zaujaani.roadsense.data.local.DeviceCalibration
import zaujaani.roadsense.data.repository.SurveyRepository
import java.util.*
import javax.inject.Inject

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val repository: SurveyRepository,
    private val bluetoothGateway: BluetoothGateway   // ✅ inject gateway
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

                // ✅ Kirim ke ESP32 (jika terkoneksi)
                val diameterCm = when (wheelDiameterUnit.lowercase(Locale.ROOT)) {
                    "cm" -> wheelDiameter
                    "mm" -> wheelDiameter / 10f
                    "m" -> wheelDiameter * 100f
                    else -> wheelDiameter // fallback, asumsi cm
                }
                val success = bluetoothGateway.sendCalibration(diameterCm, pulsesPerRotation)
                if (!success) {
                    Timber.w("⚠️ Gagal mengirim kalibrasi ke ESP32")
                    // Bisa tampilkan warning, tapi tidak menggagalkan penyimpanan
                }

                _calibrationState.value = CalibrationState.Saved
                Timber.d("Calibration saved successfully")
            } catch (e: Exception) {
                _calibrationState.value = CalibrationState.Error("Gagal menyimpan kalibrasi: ${e.message}")
                Timber.e(e, "Failed to save calibration")
            }
        }
    }

    /**
     * Konversi diameter ke meter berdasarkan unit
     */
    private fun convertDiameterToMeter(diameter: Float, unit: String): Float {
        return when (unit.lowercase(Locale.ROOT)) {
            "cm" -> diameter / 100f
            "mm" -> diameter / 1000f
            "in", "inch" -> diameter * 0.0254f
            else -> diameter // asumsi sudah dalam meter
        }
    }

    /**
     * Hitung keliling roda dalam meter
     */
    fun calculateCircumference(diameter: Float, unit: String): Float {
        val diameterInMeter = convertDiameterToMeter(diameter, unit)
        return diameterInMeter * Math.PI.toFloat()
    }

    /**
     * Hitung jarak per pulsa dalam meter
     */
    fun calculateDistancePerPulse(diameter: Float, unit: String, pulsesPerRotation: Int): Float {
        val circumference = calculateCircumference(diameter, unit)
        return circumference / pulsesPerRotation
    }

    /**
     * Ringkasan kalibrasi dalam berbagai satuan (untuk ditampilkan di UI)
     */
    fun getCalibrationSummary(
        diameter: Float,
        unit: String,
        pulsesPerRotation: Int
    ): Map<String, String> {
        val diameterInMeter = convertDiameterToMeter(diameter, unit)
        val circumferenceMeter = diameterInMeter * Math.PI.toFloat()
        val distancePerPulseMeter = circumferenceMeter / pulsesPerRotation

        // Format untuk display
        return mapOf(
            "diameter" to String.format(Locale.getDefault(), "%.2f %s", diameter, unit),
            "diameter_m" to String.format(Locale.getDefault(), "%.3f m", diameterInMeter),
            "circumference_m" to String.format(Locale.getDefault(), "%.3f m", circumferenceMeter),
            "circumference_cm" to String.format(Locale.getDefault(), "%.1f cm", circumferenceMeter * 100),
            "pulses_per_rotation" to pulsesPerRotation.toString(),
            "distance_per_pulse_mm" to String.format(Locale.getDefault(), "%.2f mm", distancePerPulseMeter * 1000),
            "distance_per_1000_pulses_m" to String.format(Locale.getDefault(), "%.2f m", distancePerPulseMeter * 1000)
        )
    }
}