package zaujaani.roadsense.data.local

import android.location.Location
import zaujaani.roadsense.core.constants.SurveyConstants
import zaujaani.roadsense.domain.model.ESP32SensorData
import zaujaani.roadsense.domain.model.QualityFlag

/**
 * Mapper untuk mengkonversi ESP32SensorData + GPS ke TelemetryRaw
 */
fun ESP32SensorData.toTelemetryRaw(
    sessionId: Long,
    gpsLocation: Location?,
    flags: List<String> = emptyList()
): TelemetryRaw {
    return TelemetryRaw(
        sessionId = sessionId,
        timestamp = this.getTimestampMillis(),
        speed = this.currentSpeed,
        vibrationZ = this.accelZ,
        hallSensorDistance = this.tripDistanceMeters,
        pulseCount = this.packetCount, // atau dari firmware jika ada
        latitude = gpsLocation?.latitude,
        longitude = gpsLocation?.longitude,
        altitude = gpsLocation?.altitude,
        gpsAccuracy = gpsLocation?.accuracy,
        quality = calculateQualityCategory(this, gpsLocation),
        flags = flags.toJsonArray(),
        temperature = this.temperature,
        humidity = null
    )
}

/**
 * Helper untuk membuat JSON array dari list string
 */
fun List<String>.toJsonArray(): String {
    return if (isEmpty()) "[]" else "[" + joinToString(",") { "\"$it\"" } + "]"
}

/**
 * Menentukan kualitas data (HIGH/MEDIUM/LOW) berdasarkan sensor dan GPS
 */
private fun calculateQualityCategory(
    sensorData: ESP32SensorData,
    gpsLocation: Location?
): String {
    val sensorScore = sensorData.calculateQualityScore()
    val gpsGood = gpsLocation?.accuracy?.let { it < SurveyConstants.GPS_EXCELLENT_ACCURACY_METERS } == true

    return when {
        sensorScore > SurveyConstants.QUALITY_HIGH_THRESHOLD && gpsGood -> "HIGH"
        sensorScore > SurveyConstants.QUALITY_MEDIUM_THRESHOLD -> "MEDIUM"
        else -> "LOW"
    }
}

/**
 * Membangun daftar QualityFlag berdasarkan data sensor dan GPS
 */
fun buildQualityFlags(
    sensorData: ESP32SensorData,
    gpsLocation: Location?
): List<String> = buildList {
    // GPS flags
    if (gpsLocation == null) {
        add(QualityFlag.GPS_UNAVAILABLE.name)
    } else if (gpsLocation.accuracy > SurveyConstants.GPS_POOR_ACCURACY_METERS) {
        add(QualityFlag.GPS_POOR_ACCURACY.name)
    }

    // Speed flags
    val speed = sensorData.currentSpeed
    if (speed < SurveyConstants.MIN_MOVING_SPEED_KMH) {
        add(QualityFlag.VEHICLE_STOPPED.name)
    } else if (speed > SurveyConstants.MAX_SURVEY_SPEED_KMH) {
        add(QualityFlag.SPEED_TOO_HIGH.name)
    }

    // Vibration flags
    if (kotlin.math.abs(sensorData.accelZ) > SurveyConstants.VIBRATION_SPIKE_THRESHOLD) {
        add(QualityFlag.VIBRATION_SPIKE.name)
    }
    if (kotlin.math.abs(sensorData.accelZ) > SurveyConstants.VIBRATION_EXTREME_THRESHOLD) {
        add(QualityFlag.VIBRATION_EXTREME.name)
    }

    // Battery flags
    if (sensorData.batteryVoltage != null) {
        val voltage = sensorData.batteryVoltage
        when {
            voltage < SurveyConstants.BATTERY_CRITICAL_VOLTAGE -> add(QualityFlag.BATTERY_CRITICAL.name)
            voltage < SurveyConstants.BATTERY_LOW_VOLTAGE -> add(QualityFlag.BATTERY_LOW.name)
            voltage < SurveyConstants.BATTERY_WARNING_VOLTAGE -> add(QualityFlag.BATTERY_WARNING.name)
        }
    }

// Error flags
    if (sensorData.errorCount != null && sensorData.errorCount > 0) {
        add("CRC_ERRORS:${sensorData.errorCount}")
    }

    // Error flags
    sensorData.errorCount?.takeIf { it > 0 }?.let {
        add("CRC_ERRORS:$it")
    }
}