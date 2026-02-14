package zaujaani.roadsense.core.constants

/**
 * RoadSense Application Constants
 *
 * Centralized constants untuk menghindari magic numbers dan meningkatkan maintainability.
 * Gunakan object ini di seluruh aplikasi untuk threshold dan konfigurasi.
 */
object SurveyConstants {

    // ============================================================================
    // GPS THRESHOLDS
    // ============================================================================

    /** GPS accuracy dianggap excellent (untuk quality HIGH) */
    const val GPS_EXCELLENT_ACCURACY_METERS = 10f

    /** GPS accuracy dianggap good (untuk quality MEDIUM) */
    const val GPS_GOOD_ACCURACY_METERS = 20f

    /** GPS accuracy threshold untuk flag GPS_POOR_ACCURACY */
    const val GPS_POOR_ACCURACY_METERS = 50f

    /** Maximum age GPS location yang dianggap fresh (milliseconds) */
    const val GPS_MAX_AGE_MS = 5000L

    /** Minimum interval untuk GPS location updates (milliseconds) */
    const val GPS_UPDATE_INTERVAL_MS = 1000L

    /** Fastest interval untuk GPS location updates (milliseconds) */
    const val GPS_UPDATE_FASTEST_INTERVAL_MS = 500L

    // ============================================================================
    // SPEED THRESHOLDS
    // ============================================================================

    /** Minimum speed untuk dianggap vehicle sedang bergerak (km/h) */
    const val MIN_MOVING_SPEED_KMH = 1f

    /** Maximum speed yang reasonable untuk survey jalan (km/h) */
    const val MAX_SURVEY_SPEED_KMH = 60f

    /** Maximum speed yang dianggap masih valid (km/h) */
    const val MAX_REASONABLE_SPEED_KMH = 100f

    /** Speed threshold untuk warning speed too high (km/h) */
    const val SPEED_WARNING_THRESHOLD_KMH = 60f

    // ============================================================================
    // VIBRATION (Z-AXIS) THRESHOLDS
    // ============================================================================

    /** Z-axis acceleration dianggap smooth/normal */
    const val VIBRATION_SMOOTH_THRESHOLD = 1.0f

    /** Z-axis acceleration dianggap moderate */
    const val VIBRATION_MODERATE_THRESHOLD = 2.0f

    /** Z-axis acceleration dianggap spike/rough */
    const val VIBRATION_SPIKE_THRESHOLD = 2.5f

    /** Z-axis acceleration dianggap extreme (untuk filtering) */
    const val VIBRATION_EXTREME_THRESHOLD = 5.0f

    /** Maximum Z-axis value yang dianggap valid (untuk data filtering) */
    const val VIBRATION_MAX_VALID = 10.0f

    // ============================================================================
    // BATTERY THRESHOLDS
    // ============================================================================

    /** Battery voltage critical - auto-pause survey */
    const val BATTERY_CRITICAL_VOLTAGE = 3.4f

    /** Battery voltage low - show warning */
    const val BATTERY_LOW_VOLTAGE = 3.6f

    /** Battery voltage warning - informational */
    const val BATTERY_WARNING_VOLTAGE = 3.7f

    /** Battery voltage normal minimum */
    const val BATTERY_NORMAL_VOLTAGE = 3.8f

    /** Battery voltage full */
    const val BATTERY_FULL_VOLTAGE = 4.2f

    // ============================================================================
    // BLUETOOTH CONNECTION
    // ============================================================================

    /** ESP32 device name untuk pairing */
    const val ESP32_DEVICE_NAME = "RoadsenseLogger-v3.7"

    /** Bluetooth SPP UUID */
    const val BLUETOOTH_SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

    /** Bluetooth connection timeout (milliseconds) */
    const val BLUETOOTH_CONNECTION_TIMEOUT_MS = 5000L

    /** Bluetooth socket read buffer size */
    const val BLUETOOTH_BUFFER_SIZE = 1024

    // ============================================================================
    // BLUETOOTH AUTO-RECONNECT
    // ============================================================================

    /** Base delay untuk reconnect pertama (milliseconds) */
    const val BLUETOOTH_RECONNECT_DELAY_BASE_MS = 1000L

    /** Maximum delay untuk reconnect (milliseconds) */
    const val BLUETOOTH_RECONNECT_DELAY_MAX_MS = 16000L

    /** Multiplier untuk exponential backoff */
    const val BLUETOOTH_RECONNECT_DELAY_MULTIPLIER = 2

    /** Maximum reconnect attempts sebelum give up */
    const val BLUETOOTH_MAX_RECONNECT_ATTEMPTS = 5

    // ============================================================================
    // NOTIFICATION
    // ============================================================================

    /** Notification ID untuk foreground service */
    const val NOTIFICATION_ID = 1001

    /** Notification channel ID */
    const val NOTIFICATION_CHANNEL_ID = "roadsense_tracking"

    /** Notification channel name */
    const val NOTIFICATION_CHANNEL_NAME = "RoadSense Tracking"

    /** Minimum interval antara notification updates (milliseconds) */
    const val NOTIFICATION_THROTTLE_MS = 3000L

    // ============================================================================
    // DATA COLLECTION
    // ============================================================================

    /** Minimum distance untuk create segment (meters) */
    const val MIN_SEGMENT_DISTANCE_METERS = 100f

    /** Maximum distance untuk single segment (meters) */
    const val MAX_SEGMENT_DISTANCE_METERS = 1000f

    /** Auto-save interval untuk telemetry data (milliseconds) */
    const val AUTO_SAVE_INTERVAL_MS = 60000L // 1 minute

    /** Batch size untuk database insert */
    const val TELEMETRY_BATCH_SIZE = 100

    /** Maximum telemetry points dalam memory sebelum flush */
    const val MAX_TELEMETRY_BUFFER_SIZE = 500

    // ============================================================================
    // QUALITY SCORING
    // ============================================================================

    /** Quality score threshold untuk HIGH */
    const val QUALITY_HIGH_THRESHOLD = 0.8f

    /** Quality score threshold untuk MEDIUM */
    const val QUALITY_MEDIUM_THRESHOLD = 0.5f

    /** Quality score threshold untuk LOW (below this) */
    const val QUALITY_LOW_THRESHOLD = 0.5f

    // ============================================================================
    // CALIBRATION
    // ============================================================================

    /** Default wheel diameter (cm) */
    const val DEFAULT_WHEEL_DIAMETER_CM = 60f

    /** Default pulses per rotation */
    const val DEFAULT_PULSES_PER_ROTATION = 20

    /** Minimum wheel diameter (cm) */
    const val MIN_WHEEL_DIAMETER_CM = 20f

    /** Maximum wheel diameter (cm) */
    const val MAX_WHEEL_DIAMETER_CM = 100f

    /** Minimum pulses per rotation */
    const val MIN_PULSES_PER_ROTATION = 1

    /** Maximum pulses per rotation */
    const val MAX_PULSES_PER_ROTATION = 100

    // ============================================================================
    // MAP DISPLAY
    // ============================================================================

    /** Default map zoom level */
    const val MAP_DEFAULT_ZOOM = 15.0

    /** Minimum map zoom level */
    const val MAP_MIN_ZOOM = 3.0

    /** Maximum map zoom level */
    const val MAP_MAX_ZOOM = 20.0

    /** GPS accuracy circle color alpha (0-255) */
    const val GPS_ACCURACY_CIRCLE_ALPHA = 50

    /** Tracking polyline width (pixels) */
    const val TRACKING_POLYLINE_WIDTH = 8f

    /** Segment polyline width (pixels) */
    const val SEGMENT_POLYLINE_WIDTH = 6f

    // ============================================================================
    // ROAD CONDITION THRESHOLDS
    // ============================================================================

    /** Vibration Z untuk kondisi GOOD */
    const val ROAD_GOOD_VIBRATION_MAX = 1.0f

    /** Vibration Z untuk kondisi FAIR */
    const val ROAD_FAIR_VIBRATION_MAX = 2.0f

    /** Vibration Z untuk kondisi LIGHT_DAMAGE */
    const val ROAD_LIGHT_DAMAGE_VIBRATION_MAX = 3.0f

    /** Vibration Z untuk kondisi HEAVY_DAMAGE (above this) */
    const val ROAD_HEAVY_DAMAGE_VIBRATION_MIN = 3.0f

    // ============================================================================
    // EXPORT & REPORTING
    // ============================================================================

    /** Maximum rows per CSV file */
    const val CSV_MAX_ROWS = 10000

    /** CSV field delimiter */
    const val CSV_DELIMITER = ","

    /** Date format untuk export */
    const val EXPORT_DATE_FORMAT = "yyyy-MM-dd_HH-mm-ss"

    /** PDF report page size */
    const val PDF_PAGE_WIDTH = 210 // A4 mm
    const val PDF_PAGE_HEIGHT = 297 // A4 mm

    // ============================================================================
    // COMMAND PROTOCOL
    // ============================================================================

    /** Command timeout (milliseconds) */
    const val COMMAND_TIMEOUT_MS = 5000L

    /** Command response timeout (milliseconds) */
    const val COMMAND_RESPONSE_TIMEOUT_MS = 3000L

    /** RS2 packet prefix */
    const val RS2_PACKET_PREFIX = "RS2,"

    // ============================================================================
    // ERROR MESSAGES
    // ============================================================================

    object ErrorMessages {
        const val BLUETOOTH_NOT_AVAILABLE = "Bluetooth tidak tersedia di perangkat ini"
        const val BLUETOOTH_DISABLED = "Bluetooth dimatikan. Silakan aktifkan Bluetooth."
        const val DEVICE_NOT_FOUND = "ESP32 tidak ditemukan. Pastikan device sudah di-pair."
        const val GPS_UNAVAILABLE = "GPS tidak tersedia. Survey tetap berjalan dengan sensor."
        const val BATTERY_CRITICAL = "Battery ESP32 critical! Charge segera."
        const val STORAGE_FULL = "Storage penuh. Hapus data lama atau pindahkan ke external storage."
        const val PERMISSION_DENIED = "Izin lokasi dan Bluetooth diperlukan untuk survey."
    }

    // ============================================================================
    // SUCCESS MESSAGES
    // ============================================================================

    object SuccessMessages {
        const val SURVEY_STARTED = "Survey dimulai!"
        const val SURVEY_PAUSED = "Survey dijeda"
        const val SURVEY_RESUMED = "Survey dilanjutkan"
        const val SURVEY_STOPPED = "Survey selesai. Data tersimpan."
        const val CALIBRATION_SAVED = "Kalibrasi berhasil disimpan"
        const val SEGMENT_CREATED = "Segment jalan berhasil dibuat"
        const val DATA_EXPORTED = "Data berhasil diekspor"
    }
}