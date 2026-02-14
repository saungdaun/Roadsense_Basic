package zaujaani.roadsense.features.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsense.core.bluetooth.BluetoothGateway
import zaujaani.roadsense.data.local.DeviceCalibration
import zaujaani.roadsense.data.repository.ImprovedSurveyRepository
import java.util.*
import javax.inject.Inject

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val repository: ImprovedSurveyRepository,
    private val bluetoothGateway: BluetoothGateway
) : ViewModel() {

    // ========== STATE FLOW ==========
    private val _calibrationState = MutableStateFlow<CalibrationState>(CalibrationState.Loading)
    val calibrationState: StateFlow<CalibrationState> = _calibrationState.asStateFlow()

    private var currentCalibrationId: Long? = null

    // ========== SEALED STATE ==========
    sealed class CalibrationState {
        object Loading : CalibrationState()
        data class Loaded(val calibration: DeviceCalibration?) : CalibrationState()
        data class Error(val message: String) : CalibrationState()
        object Saved : CalibrationState()
    }

    // ========== PUBLIC API ==========

    /**
     * Muat kalibrasi aktif dari database
     */
    fun loadCalibration() {
        viewModelScope.launch {
            _calibrationState.value = CalibrationState.Loading
            try {
                val calibration = repository.getActiveCalibration()
                currentCalibrationId = calibration?.id
                _calibrationState.value = CalibrationState.Loaded(calibration)
                Timber.d("✅ Loaded calibration: $calibration")
            } catch (e: Exception) {
                _calibrationState.value = CalibrationState.Error("Gagal memuat kalibrasi: ${e.message}")
                Timber.e(e, "❌ Failed to load calibration")
            }
        }
    }

    /**
     * Simpan kalibrasi dan kirim ke ESP32 (jika terhubung)
     */
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
        // ✅ Validasi input
        if (wheelDiameter <= 0) {
            _calibrationState.value = CalibrationState.Error("Diameter roda harus > 0")
            return
        }
        if (pulsesPerRotation <= 0) {
            _calibrationState.value = CalibrationState.Error("Pulsa per putaran harus > 0")
            return
        }

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

                // 1️⃣ Simpan ke database
                repository.saveCalibration(calibration)
                currentCalibrationId = calibration.id

                // 2️⃣ Kirim ke ESP32 (dengan pengecekan koneksi)
                sendCalibrationToESP32(wheelDiameter, wheelDiameterUnit, pulsesPerRotation)

                _calibrationState.value = CalibrationState.Saved
                Timber.d("✅ Calibration saved successfully (ID: ${calibration.id})")

            } catch (e: Exception) {
                _calibrationState.value = CalibrationState.Error("Gagal menyimpan kalibrasi: ${e.message}")
                Timber.e(e, "❌ Failed to save calibration")
            }
        }
    }

    /**
     * Kirim parameter kalibrasi ke ESP32 – dengan waiting connection
     */
    private suspend fun sendCalibrationToESP32(
        diameter: Float,
        unit: String,
        pulses: Int
    ) {
        // Tunggu sampai Bluetooth terhubung (maksimal 2 detik)
        val isConnected = bluetoothGateway.connectionState.firstOrNull { state ->
            state is BluetoothGateway.ConnectionState.Connected
        } != null

        if (!isConnected) {
            Timber.w("⚠️ Bluetooth tidak terhubung – kalibrasi hanya disimpan lokal")
            return
        }

        // Konversi diameter ke cm (ESP32 menggunakan cm)
        val diameterCm = when (unit.lowercase(Locale.ROOT)) {
            "cm"  -> diameter
            "mm"  -> diameter / 10f
            "m"   -> diameter * 100f
            "in", "inch" -> diameter * 2.54f
            else  -> diameter // fallback, asumsi cm
        }

        val success = bluetoothGateway.sendCalibration(diameterCm, pulses)
        if (success) {
            Timber.d("📲 Calibration sent to ESP32: ${diameterCm}cm, ${pulses}pulse/rev")
        } else {
            Timber.w("⚠️ Gagal mengirim kalibrasi ke ESP32 – device mungkin sibuk")
        }
    }

    // ========== UTILITY FUNGSI KALIBRASI ==========

    /**
     * Konversi diameter ke meter
     */
    private fun diameterToMeter(diameter: Float, unit: String): Float = when (unit.lowercase(Locale.ROOT)) {
        "cm"  -> diameter / 100f
        "mm"  -> diameter / 1000f
        "in", "inch" -> diameter * 0.0254f
        else  -> diameter // asumsi meter
    }

    /**
     * Hitung keliling roda dalam meter
     */
    fun calculateCircumference(diameter: Float, unit: String): Float {
        val diameterM = diameterToMeter(diameter, unit)
        return diameterM * Math.PI.toFloat()
    }

    /**
     * Hitung jarak per pulsa dalam meter
     */
    fun calculateDistancePerPulse(diameter: Float, unit: String, pulsesPerRotation: Int): Float {
        if (pulsesPerRotation <= 0) return 0f
        val circumference = calculateCircumference(diameter, unit)
        return circumference / pulsesPerRotation
    }

    /**
     * Ringkasan kalibrasi untuk UI
     */
    fun getCalibrationSummary(
        diameter: Float,
        unit: String,
        pulsesPerRotation: Int
    ): Map<String, String> {
        if (diameter <= 0 || pulsesPerRotation <= 0) {
            return mapOf("error" to "Parameter tidak valid")
        }

        val diameterM = diameterToMeter(diameter, unit)
        val circumferenceM = diameterM * Math.PI.toFloat()
        val distancePerPulseM = circumferenceM / pulsesPerRotation

        return mapOf(
            "diameter" to String.format(Locale.getDefault(), "%.2f %s", diameter, unit),
            "diameter_m" to String.format(Locale.getDefault(), "%.3f m", diameterM),
            "circumference_m" to String.format(Locale.getDefault(), "%.3f m", circumferenceM),
            "circumference_cm" to String.format(Locale.getDefault(), "%.1f cm", circumferenceM * 100),
            "pulses_per_rotation" to pulsesPerRotation.toString(),
            "distance_per_pulse_mm" to String.format(Locale.getDefault(), "%.2f mm", distancePerPulseM * 1000),
            "distance_per_1000_pulses_m" to String.format(Locale.getDefault(), "%.2f m", distancePerPulseM * 1000)
        )
    }
}