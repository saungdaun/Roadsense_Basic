package zaujaani.roadsense.domain.model

import androidx.annotation.Keep


/**
 * Quality flags for road segment data quality assessment
 */
@Keep
enum class QualityFlag {
    HIGH_CONFIDENCE,
    MEDIUM_CONFIDENCE,
    LOW_CONFIDENCE,
    GPS_UNAVAILABLE,
    SENSOR_DISCONNECTED,
    VIBRATION_SPIKE,
    POOR_GPS_ACCURACY,
    MANUAL_OVERRIDE,
    SPEED_TOO_HIGH,
    SIGNAL_WEAK,
    BATTERY_LOW,
    CALIBRATION_NEEDED,
    DATA_GAP,
    OUTLIER_DETECTED,
    WEATHER_INTERFERENCE,
    VEHICLE_VIBRATION,
    ROAD_CONSTRUCTION,
    TUNNEL_NO_GPS,
    BRIDGE_EFFECT,
    URBAN_CANYON;

    companion object {
        /**
         * Convert list of quality flags to JSON string
         */
        @JvmStatic
        fun List<QualityFlag>.toJsonString(): String {
            return this.joinToString(",") { it.name }
        }

        /**
         * Convert JSON string to list of quality flags
         */
        @JvmStatic
        fun String.toQualityFlags(): List<QualityFlag> {
            return if (this.isBlank()) {
                emptyList()
            } else {
                this.split(",").mapNotNull { flagName ->
                    try {
                        valueOf(flagName.trim())
                    } catch (e: IllegalArgumentException) {
                        null // Silently ignore unknown flags
                    }
                }
            }
        }
    }
}