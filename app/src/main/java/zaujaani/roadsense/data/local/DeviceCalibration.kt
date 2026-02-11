package zaujaani.roadsense.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_calibration")
data class DeviceCalibration(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceName: String, // Nama device/kendaraan
    val wheelDiameter: Float, // Diameter roda dalam cm
    val wheelDiameterUnit: String = "cm", // "cm" atau "inch"
    val pulsesPerRotation: Int, // Pulses per rotation dari Hall sensor
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsed: Long? = null,
    val isActive: Boolean = true,
    val notes: String? = null,
    val vehicleType: String? = null, // Jenis kendaraan
    val tirePressure: Float? = null, // Tekanan ban (PSI)
    val loadWeight: Float? = null // Beban kendaraan (kg)
) {
    // Hitung circumference dari diameter
    val wheelCircumference: Float
        get() = wheelDiameter * Math.PI.toFloat()

    // Konversi ke meter jika diperlukan
    val wheelCircumferenceMeters: Float
        get() = when (wheelDiameterUnit.lowercase()) {
            "cm" -> wheelCircumference / 100f
            "inch" -> wheelCircumference * 0.0254f
            else -> wheelCircumference / 100f
        }
}