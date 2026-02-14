package zaujaani.roadsense.core.extensions

import android.location.Location
import zaujaani.roadsense.core.constants.SurveyConstants
import zaujaani.roadsense.domain.model.ESP32SensorData
import zaujaani.roadsense.domain.model.QualityFlag
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * Extension Functions untuk RoadSense
 *
 * Meningkatkan code readability dan mengurangi boilerplate code
 */

// ============================================================================
// LOCATION EXTENSIONS
// ============================================================================

/**
 * Check if Location is fresh (not stale)
 */
fun Location.isFresh(maxAgeMs: Long = SurveyConstants.GPS_MAX_AGE_MS): Boolean {
    val age = System.currentTimeMillis() - this.time
    return age < maxAgeMs
}

/**
 * Check if Location is a good GPS fix
 */
fun Location.isGoodFix(): Boolean {
    return this.accuracy < SurveyConstants.GPS_GOOD_ACCURACY_METERS &&
            this.hasSpeed() &&
            this.speed >= 0f &&
            this.isFresh()
}

/**
 * Check if Location is excellent quality
 */
fun Location.isExcellentFix(): Boolean {
    return this.accuracy < SurveyConstants.GPS_EXCELLENT_ACCURACY_METERS &&
            this.hasSpeed() &&
            this.speed >= 0f &&
            this.isFresh()
}

/**
 * Get GPS quality description
 */
fun Location.qualityDescription(): String {
    return when {
        !this.isFresh() -> "Stale (${getAgeSecs()}s old)"
        this.accuracy < SurveyConstants.GPS_EXCELLENT_ACCURACY_METERS -> "Excellent (±${accuracy.toInt()}m)"
        this.accuracy < SurveyConstants.GPS_GOOD_ACCURACY_METERS -> "Good (±${accuracy.toInt()}m)"
        this.accuracy < SurveyConstants.GPS_POOR_ACCURACY_METERS -> "Fair (±${accuracy.toInt()}m)"
        else -> "Poor (±${accuracy.toInt()}m)"
    }
}

/**
 * Get age of location in seconds
 */
fun Location.getAgeSecs(): Int {
    val ageMs = System.currentTimeMillis() - this.time
    return (ageMs / 1000).toInt()
}

/**
 * Get GPS quality as percentage (0-100)
 */
fun Location.getQualityPercentage(): Int {
    return when {
        !this.isFresh() -> 0
        this.accuracy < 5f -> 100
        this.accuracy < 10f -> 90
        this.accuracy < 20f -> 75
        this.accuracy < 50f -> 50
        else -> 25
    }.coerceIn(0, 100)
}

// ============================================================================
// ESP32 SENSOR DATA EXTENSIONS
// ============================================================================

/**
 * Check if sensor data indicates vehicle is moving
 */
fun ESP32SensorData.isMoving(): Boolean {
    return this.currentSpeed >= SurveyConstants.MIN_MOVING_SPEED_KMH
}

/**
 * Check if speed is within reasonable range
 */
fun ESP32SensorData.hasReasonableSpeed(): Boolean {
    return this.currentSpeed >= 0f &&
            this.currentSpeed <= SurveyConstants.MAX_REASONABLE_SPEED_KMH
}

/**
 * Check if speed is safe for survey
 */
fun ESP32SensorData.hasSafeSurveySpeed(): Boolean {
    return this.currentSpeed >= SurveyConstants.MIN_MOVING_SPEED_KMH &&
            this.currentSpeed <= SurveyConstants.MAX_SURVEY_SPEED_KMH
}

/**
 * Get vibration level
 */
fun ESP32SensorData.getVibrationLevel(): VibrationLevel {
    val absZ = abs(this.accelZ)
    return when {
        absZ < SurveyConstants.VIBRATION_SMOOTH_THRESHOLD -> VibrationLevel.SMOOTH
        absZ < SurveyConstants.VIBRATION_MODERATE_THRESHOLD -> VibrationLevel.MODERATE
        absZ < SurveyConstants.VIBRATION_SPIKE_THRESHOLD -> VibrationLevel.ROUGH
        absZ < SurveyConstants.VIBRATION_EXTREME_THRESHOLD -> VibrationLevel.VERY_ROUGH
        else -> VibrationLevel.EXTREME
    }
}

enum class VibrationLevel {
    SMOOTH,
    MODERATE,
    ROUGH,
    VERY_ROUGH,
    EXTREME
}

/**
 * Get battery level percentage
 */
fun ESP32SensorData.getBatteryPercentage(): Int? {
    return this.batteryVoltage?.let { voltage ->
        when {
            voltage >= SurveyConstants.BATTERY_FULL_VOLTAGE -> 100
            voltage >= SurveyConstants.BATTERY_NORMAL_VOLTAGE -> 75
            voltage >= SurveyConstants.BATTERY_WARNING_VOLTAGE -> 50
            voltage >= SurveyConstants.BATTERY_LOW_VOLTAGE -> 25
            voltage >= SurveyConstants.BATTERY_CRITICAL_VOLTAGE -> 10
            else -> 5
        }.coerceIn(0, 100)
    }
}

/**
 * Get battery status
 */
fun ESP32SensorData.getBatteryStatus(): BatteryStatus? {
    return this.batteryVoltage?.let { voltage ->
        when {
            voltage >= SurveyConstants.BATTERY_NORMAL_VOLTAGE -> BatteryStatus.GOOD
            voltage >= SurveyConstants.BATTERY_WARNING_VOLTAGE -> BatteryStatus.FAIR
            voltage >= SurveyConstants.BATTERY_LOW_VOLTAGE -> BatteryStatus.LOW
            voltage >= SurveyConstants.BATTERY_CRITICAL_VOLTAGE -> BatteryStatus.CRITICAL
            else -> BatteryStatus.DEAD
        }
    }
}

enum class BatteryStatus {
    GOOD,
    FAIR,
    LOW,
    CRITICAL,
    DEAD
}

/**
 * Check if battery needs charging
 */
fun ESP32SensorData.needsCharging(): Boolean {
    return this.batteryVoltage?.let { it < SurveyConstants.BATTERY_LOW_VOLTAGE } ?: false
}

/**
 * Check if battery is critical
 */
fun ESP32SensorData.isBatteryCritical(): Boolean {
    return this.batteryVoltage?.let { it < SurveyConstants.BATTERY_CRITICAL_VOLTAGE } ?: false
}

/**
 * Get trip distance in kilometers
 */
fun ESP32SensorData.getTripDistanceKm(): Float {
    return this.tripDistanceMeters / 1000f
}

/**
 * Get odometer in kilometers
 */
fun ESP32SensorData.getOdometerKm(): Float {
    return this.odometerMeters / 1000f
}

/**
 * Format distance untuk display
 */
fun ESP32SensorData.formatTripDistance(): String {
    val km = getTripDistanceKm()
    return if (km < 1f) {
        "${tripDistanceMeters.toInt()} m"
    } else {
        "%.2f km".format(km)
    }
}

/**
 * Format speed untuk display
 */
fun ESP32SensorData.formatSpeed(): String {
    return "%.1f km/h".format(currentSpeed)
}

/**
 * Get comprehensive quality flags
 */
/**
 * Get comprehensive quality flags
 */
fun ESP32SensorData.generateQualityFlags(gpsLocation: Location?): List<QualityFlag> {
    return buildList {
        // GPS checks
        if (gpsLocation == null) {
            add(QualityFlag.GPS_UNAVAILABLE)
        } else if (!gpsLocation.isFresh()) {
            add(QualityFlag.GPS_STALE)
        } else if (gpsLocation.accuracy > SurveyConstants.GPS_POOR_ACCURACY_METERS) {
            add(QualityFlag.GPS_POOR_ACCURACY)
        }

        // Speed checks
        if (!isMoving()) {
            add(QualityFlag.VEHICLE_STOPPED)
        } else if (currentSpeed > SurveyConstants.MAX_SURVEY_SPEED_KMH) {
            add(QualityFlag.SPEED_TOO_HIGH)
        }

        // Vibration checks
        if (abs(accelZ) > SurveyConstants.VIBRATION_SPIKE_THRESHOLD) {
            add(QualityFlag.VIBRATION_SPIKE)
        }

        // Battery checks
        if (batteryVoltage != null) {
            when {
                batteryVoltage < SurveyConstants.BATTERY_CRITICAL_VOLTAGE ->
                    add(QualityFlag.BATTERY_CRITICAL)
                batteryVoltage < SurveyConstants.BATTERY_LOW_VOLTAGE ->
                    add(QualityFlag.BATTERY_LOW)
            }
        }

        // Error checks
        if (errorCount != null && errorCount > 0) {
            add(QualityFlag.CRC_ERRORS)
        }
    }
}



// ============================================================================
// FLOAT EXTENSIONS
// ============================================================================

/**
 * Format meters to display string
 */
fun Float.toDistanceString(): String {
    return if (this < 1000f) {
        "${this.toInt()} m"
    } else {
        "%.2f km".format(this / 1000f)
    }
}

/**
 * Format speed to display string
 */
fun Float.toSpeedString(): String {
    return "%.1f km/h".format(this)
}

/**
 * Format battery voltage to display string
 */
fun Float.toBatteryString(): String {
    return "%.2f V".format(this)
}

/**
 * Format temperature to display string
 */
fun Float.toTemperatureString(): String {
    return "%.1f°C".format(this)
}

// ============================================================================
// LONG EXTENSIONS (Timestamp)
// ============================================================================

/**
 * Format timestamp to readable date/time
 */
fun Long.toDateTimeString(): String {
    val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(this))
}

/**
 * Format timestamp to date only
 */
fun Long.toDateString(): String {
    val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    return sdf.format(Date(this))
}

/**
 * Format timestamp to time only
 */
fun Long.toTimeString(): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(this))
}

/**
 * Get duration from timestamp to now
 */
fun Long.getDurationToNow(): String {
    val durationMs = System.currentTimeMillis() - this
    val seconds = durationMs / 1000
    val minutes = seconds / 60
    val hours = minutes / 60

    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

// ============================================================================
// STRING EXTENSIONS
// ============================================================================

/**
 * Parse ISO timestamp to Long (milliseconds)
 */
fun String.parseIsoTimestamp(): Long? {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.parse(this)?.time
    } catch (e: Exception) {
        null
    }
}

/**
 * Truncate string to max length
 */
fun String.truncate(maxLength: Int, ellipsis: String = "..."): String {
    return if (this.length <= maxLength) {
        this
    } else {
        this.substring(0, maxLength - ellipsis.length) + ellipsis
    }
}

// ============================================================================
// COLLECTION EXTENSIONS
// ============================================================================

/**
 * Get average of Float list
 */
fun List<Float>.averageOrZero(): Float {
    return if (this.isEmpty()) 0f else this.average().toFloat()
}

/**
 * Get median of Float list
 */
fun List<Float>.median(): Float {
    if (this.isEmpty()) return 0f
    val sorted = this.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2f
    } else {
        sorted[middle]
    }
}

/**
 * Calculate standard deviation
 */
fun List<Float>.standardDeviation(): Float {
    if (this.size < 2) return 0f
    val mean = this.average().toFloat()
    val variance = this.map { (it - mean) * (it - mean) }.average().toFloat()
    return kotlin.math.sqrt(variance)
}

// ============================================================================
// RESULT EXTENSIONS
// ============================================================================

/**
 * Map Result<T> to Result<R>
 */
inline fun <T, R> Result<T>.mapResult(transform: (T) -> R): Result<R> {
    return try {
        Result.success(transform(this.getOrThrow()))
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Handle Result with success/error callbacks
 */
inline fun <T> Result<T>.handle(
    onSuccess: (T) -> Unit,
    onError: (Exception) -> Unit
) {
    this.fold(
        onSuccess = onSuccess,
        onFailure = { onError(it as? Exception ?: Exception(it.message)) }
    )
}