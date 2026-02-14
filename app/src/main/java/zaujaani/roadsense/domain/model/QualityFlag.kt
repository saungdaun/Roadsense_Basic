package zaujaani.roadsense.domain.model

/**
 * Quality Flags untuk audit trail dan data validation
 *
 * Digunakan untuk menandai data point dengan kondisi khusus
 * yang perlu diperhatikan saat analisis data survey.
 */
enum class QualityFlag {
    // ============================================================================
    // GPS RELATED FLAGS
    // ============================================================================

    /** GPS tidak tersedia sama sekali */
    GPS_UNAVAILABLE,

    /** GPS data sudah terlalu lama (stale) */
    GPS_STALE,

    /** GPS accuracy buruk (>20m) */
    GPS_POOR_ACCURACY,

    /** GPS accuracy sangat buruk (>50m) */
    GPS_VERY_POOR_ACCURACY,

    // ============================================================================
    // SPEED RELATED FLAGS
    // ============================================================================

    /** Kendaraan berhenti (speed < 1 km/h) */
    VEHICLE_STOPPED,

    /** Kecepatan terlalu tinggi untuk survey (>60 km/h) */
    SPEED_TOO_HIGH,

    /** Kecepatan tidak masuk akal (>100 km/h atau negatif) */
    SPEED_UNREASONABLE,

    // ============================================================================
    // VIBRATION RELATED FLAGS
    // ============================================================================

    /** Vibration spike terdeteksi (|Z| > 2.5) */
    VIBRATION_SPIKE,

    /** Vibration extreme (|Z| > 5.0) */
    VIBRATION_EXTREME,

    // ============================================================================
    // BATTERY RELATED FLAGS
    // ============================================================================

    /** Battery ESP32 critical (<3.4V) */
    BATTERY_CRITICAL,

    /** Battery ESP32 low (<3.6V) */
    BATTERY_LOW,

    /** Battery ESP32 warning (<3.7V) */
    BATTERY_WARNING,

    // ============================================================================
    // DATA INTEGRITY FLAGS
    // ============================================================================

    /** CRC errors terdeteksi dari firmware */
    CRC_ERRORS,

    /** Packet parsing error */
    PARSING_ERROR,

    /** Data tidak valid untuk recording */
    INVALID_DATA,

    // ============================================================================
    // CONFIDENCE FLAGS
    // ============================================================================

    /** Data memiliki confidence tinggi */
    HIGH_CONFIDENCE,

    // ============================================================================
    // SENSOR FLAGS
    // ============================================================================

    /** Sensor tidak merespon */
    SENSOR_NO_RESPONSE,

    /** Sensor data anomaly */
    SENSOR_ANOMALY,

    // ============================================================================
    // ROAD CONDITION FLAGS
    // ============================================================================

    /** Jalan rusak berat terdeteksi */
    ROAD_HEAVY_DAMAGE,

    /** Jalan berlubang terdeteksi */
    ROAD_POTHOLE,

    /** Permukaan jalan kasar */
    ROAD_ROUGH_SURFACE;

    /**
     * Get user-friendly description
     */
    fun getDescription(): String {
        return when (this) {
            GPS_UNAVAILABLE -> "GPS tidak tersedia"
            GPS_STALE -> "GPS data lama"
            GPS_POOR_ACCURACY -> "GPS akurasi buruk"
            GPS_VERY_POOR_ACCURACY -> "GPS akurasi sangat buruk"

            VEHICLE_STOPPED -> "Kendaraan berhenti"
            SPEED_TOO_HIGH -> "Kecepatan terlalu tinggi"
            SPEED_UNREASONABLE -> "Kecepatan tidak wajar"

            VIBRATION_SPIKE -> "Getaran tinggi"
            VIBRATION_EXTREME -> "Getaran ekstrim"

            BATTERY_CRITICAL -> "Battery kritis"
            BATTERY_LOW -> "Battery lemah"
            BATTERY_WARNING -> "Battery peringatan"

            CRC_ERRORS -> "Error validasi data"
            PARSING_ERROR -> "Error parsing data"
            INVALID_DATA -> "Data tidak valid"

            HIGH_CONFIDENCE -> "Confidence tinggi"

            SENSOR_NO_RESPONSE -> "Sensor tidak merespon"
            SENSOR_ANOMALY -> "Data sensor anomali"

            ROAD_HEAVY_DAMAGE -> "Jalan rusak berat"
            ROAD_POTHOLE -> "Lubang jalan"
            ROAD_ROUGH_SURFACE -> "Permukaan kasar"
        }
    }

    /**
     * Get severity level (for UI coloring)
     */
    fun getSeverity(): Severity {
        return when (this) {
            GPS_UNAVAILABLE,
            BATTERY_CRITICAL,
            SPEED_UNREASONABLE,
            VIBRATION_EXTREME,
            INVALID_DATA -> Severity.CRITICAL

            GPS_POOR_ACCURACY,
            SPEED_TOO_HIGH,
            VIBRATION_SPIKE,
            BATTERY_LOW,
            CRC_ERRORS,
            SENSOR_ANOMALY,
            ROAD_HEAVY_DAMAGE -> Severity.HIGH

            GPS_STALE,
            BATTERY_WARNING,
            PARSING_ERROR,
            ROAD_POTHOLE -> Severity.MEDIUM

            GPS_VERY_POOR_ACCURACY,
            VEHICLE_STOPPED,
            SENSOR_NO_RESPONSE,
            ROAD_ROUGH_SURFACE,
            HIGH_CONFIDENCE -> Severity.LOW
        }
    }

    enum class Severity {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW
    }
}