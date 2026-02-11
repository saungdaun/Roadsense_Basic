package zaujaani.roadsense.domain.model

import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

/**
 * ESP32 Sensor Data Model
 *
 * ⚠️ CRITICAL: Firmware sends data in FINAL units (meters, km/h)
 * Android MUST NOT do any conversion - this is SINGLE SOURCE OF TRUTH principle
 *
 * RS2 Format from firmware:
 * RS2,ODO=1234.56,TRIP=567.89,SPD=45.2,Z=-0.15,TIME=2025-02-11T10:30:45Z
 */
data class ESP32SensorData(
    val odometerMeters: Float,      // Total odometer in meters (from firmware)
    val tripDistanceMeters: Float,  // Trip distance in meters (from firmware)
    val currentSpeed: Float,        // Speed in km/h (from firmware)
    val accelZ: Float,              // Z-axis acceleration (raw from firmware)
    val timestamp: String,          // ISO timestamp from firmware
    val batteryVoltage: Float? = null,     // Battery voltage if available
    val temperature: Float? = null,        // Temperature if available
    val sessionId: Long? = null,           // Session ID from firmware
    val packetCount: Int? = null,          // Packet counter from firmware
    val errorCount: Int? = null,           // Error counter from firmware
    val parsedAt: Long = System.currentTimeMillis()  // When Android parsed this
) {
    companion object {
        private const val RS2_PREFIX = "RS2,"

        /**
         * Parse RS2 format from ESP32 firmware
         *
         * Format: RS2,ODO=<meters>,TRIP=<meters>,SPD=<km/h>,Z=<accel>,TIME=<iso_timestamp>[,BAT=<volts>][,TEMP=<celsius>][,SID=<id>][,PKT=<count>][,ERR=<count>]
         *
         * Example: RS2,ODO=1234.56,TRIP=567.89,SPD=45.2,Z=-0.15,TIME=2025-02-11T10:30:45Z,BAT=3.8,TEMP=28.5,SID=12345,PKT=1000,ERR=2
         *
         * ⚠️ IMPORTANT:
         * - NO CONVERSION is done here!
         * - All values are used AS-IS from firmware
         * - Firmware has already done pulse→meter conversion
         */
        fun fromBluetoothPacket(packet: String): ESP32SensorData? {
            return try {
                if (!packet.startsWith(RS2_PREFIX)) {
                    Timber.w("Invalid packet prefix: $packet")
                    return null
                }

                val data = packet.removePrefix(RS2_PREFIX).trim()
                val params = parseKeyValuePairs(data)

                // Validate required fields
                val odo = params["ODO"]?.toFloatOrNull()
                val trip = params["TRIP"]?.toFloatOrNull()
                val spd = params["SPD"]?.toFloatOrNull()
                val z = params["Z"]?.toFloatOrNull()
                val time = params["TIME"]

                if (odo == null || trip == null || spd == null || z == null || time.isNullOrBlank()) {
                    Timber.w("Missing required fields in packet: $packet")
                    return null
                }

                ESP32SensorData(
                    odometerMeters = odo,              // ✅ Direct from firmware (in meters)
                    tripDistanceMeters = trip,         // ✅ Direct from firmware (in meters)
                    currentSpeed = spd,                // ✅ Direct from firmware (in km/h)
                    accelZ = z,                        // ✅ Direct from firmware (raw acceleration)
                    timestamp = time,                  // ✅ ISO timestamp from firmware
                    batteryVoltage = params["BAT"]?.toFloatOrNull(),
                    temperature = params["TEMP"]?.toFloatOrNull(),
                    sessionId = params["SID"]?.toLongOrNull(),
                    packetCount = params["PKT"]?.toIntOrNull(),
                    errorCount = params["ERR"]?.toIntOrNull()
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to parse RS2 packet: $packet")
                null
            }
        }

        /**
         * Parse key=value pairs from RS2 data string
         */
        private fun parseKeyValuePairs(data: String): Map<String, String> {
            val result = mutableMapOf<String, String>()

            // Split by comma, but handle ISO timestamp which contains colons
            val parts = data.split(",")

            for (part in parts) {
                val kv = part.split("=", limit = 2)
                if (kv.size == 2) {
                    result[kv[0].trim()] = kv[1].trim()
                }
            }

            return result
        }

        /**
         * Validate if GPS fix is good quality
         */
        fun isGoodGPSFix(accuracy: Float?, speed: Float?, age: Long?): Boolean {
            return accuracy != null && accuracy < 20f &&
                    speed != null && speed >= 0f &&
                    age != null && age < 5000 // < 5 seconds old
        }
    }

    /**
     * Calculate data quality score (0.0 - 1.0)
     * Based on speed validity and Z-axis reasonableness
     */
    fun calculateQualityScore(): Float {
        var score = 1.0f

        // Speed validation
        if (currentSpeed < 0f || currentSpeed > 100f) {
            score -= 0.3f // Unreasonable speed
        } else if (currentSpeed > 60f) {
            score -= 0.1f // Too fast for survey (warning)
        }

        // Z-axis validation
        if (kotlin.math.abs(accelZ) > 5.0f) {
            score -= 0.3f // Extreme vibration
        } else if (kotlin.math.abs(accelZ) > 2.5f) {
            score -= 0.1f // High vibration (warning)
        }

        // Battery warning (if available)
        batteryVoltage?.let { voltage ->
            if (voltage < 3.4f) {
                score -= 0.2f // Low battery
            } else if (voltage < 3.6f) {
                score -= 0.1f // Battery warning
            }
        }

        return score.coerceIn(0f, 1f)
    }

    /**
     * Check if this data point is valid for recording
     */
    fun isValidForRecording(): Boolean {
        return currentSpeed > 0f &&      // Vehicle is moving
                currentSpeed < 100f &&     // Speed is reasonable
                kotlin.math.abs(accelZ) < 10f  // Z-axis not extreme
    }

    /**
     * Get human-readable status
     */
    fun getStatus(): String {
        return when {
            !isValidForRecording() -> "INVALID"
            currentSpeed < 1f -> "STOPPED"
            kotlin.math.abs(accelZ) > 2.5f -> "HIGH_VIBRATION"
            batteryVoltage != null && batteryVoltage < 3.6f -> "LOW_BATTERY"
            else -> "NORMAL"
        }
    }
}