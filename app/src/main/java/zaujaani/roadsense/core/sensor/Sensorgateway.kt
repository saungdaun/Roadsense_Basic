package zaujaani.roadsense.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sensor Gateway - Mengelola accelerometer sensor
 *
 * CATATAN PENTING:
 * Sensor HP hanya BACKUP jika ESP32 tidak tersedia.
 * Data utama tetap dari ESP32 karena lebih akurat.
 */
@Singleton
class SensorGateway @Inject constructor(
    private val context: Context
) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _accelData = MutableStateFlow(AccelData(0f, 0f, 0f))
    val accelData: StateFlow<AccelData> = _accelData.asStateFlow()

    private val _zAxisFiltered = MutableStateFlow(0f)
    val zAxisFiltered: StateFlow<Float> = _zAxisFiltered.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    // Low-pass filter for smoothing
    private val alpha = 0.1f
    private var filteredZ = 0f

    data class AccelData(
        val x: Float,
        val y: Float,
        val z: Float
    )

    /**
     * Start listening to accelerometer
     */
    fun start() {
        if (accelerometer == null) {
            Timber.e("❌ Accelerometer not available")
            return
        }

        sensorManager?.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_NORMAL
        )

        _isActive.value = true
        Timber.d("📱 Accelerometer started (BACKUP mode)")
    }

    /**
     * Stop listening to accelerometer
     */
    fun stop() {
        sensorManager?.unregisterListener(this)
        _isActive.value = false
        Timber.d("🛑 Accelerometer stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            _accelData.value = AccelData(x, y, z)

            // Apply low-pass filter to Z-axis
            filteredZ = alpha * z + (1 - alpha) * filteredZ
            _zAxisFiltered.value = filteredZ

            Timber.v("📱 Accel: x=$x, y=$y, z=$z, filtered_z=$filteredZ")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Timber.d("Sensor accuracy changed: $accuracy")
    }

    /**
     * Check if accelerometer is available
     */
    fun isAvailable(): Boolean {
        return accelerometer != null
    }

    /**
     * Calculate IRI approximation from Z-axis acceleration
     * (Simplified formula, not as accurate as dedicated sensor)
     */
    fun calculateApproximateIRI(zAccel: Float): Double {
        // Simplified IRI calculation
        // Real IRI requires integration over distance and more complex processing
        val absZ = kotlin.math.abs(zAccel - 9.81f) // Remove gravity

        return when {
            absZ < 0.5 -> 1.5  // EXCELLENT
            absZ < 1.0 -> 3.0  // GOOD
            absZ < 2.0 -> 5.0  // FAIR
            absZ < 3.0 -> 7.0  // POOR
            else -> 9.0        // BAD
        }
    }
}