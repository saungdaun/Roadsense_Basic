package zaujaani.roadsense.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsense.MainActivity
import zaujaani.roadsense.R
import zaujaani.roadsense.core.bluetooth.BluetoothGateway
import zaujaani.roadsense.core.gps.GPSGateway
import zaujaani.roadsense.data.local.TelemetryRaw
import zaujaani.roadsense.data.repository.SurveyRepository
import zaujaani.roadsense.data.repository.TelemetryRepository
import zaujaani.roadsense.domain.engine.SurveyEngine
import zaujaani.roadsense.domain.model.ESP32SensorData
import zaujaani.roadsense.domain.model.QualityFlag
import javax.inject.Inject

/**
 * Tracking Foreground Service - The Heart of RoadSense
 *
 * CRITICAL RESPONSIBILITIES:
 * ✅ Maintain Bluetooth connection with ESP32 (auto-reconnect)
 * ✅ Receive and validate telemetry data from ESP32
 * ✅ Fuse GPS location with sensor data
 * ✅ Save raw telemetry to database (audit trail)
 * ✅ Update real-time UI through event bus
 * ✅ Display persistent notification
 * ✅ Keep survey running even in background
 *
 * DESIGN PRINCIPLES:
 * - Sensor is PRIMARY distance source, GPS is position reference only
 * - NEVER stop survey on GPS drop
 * - All data saved with quality flags for transparency
 * - Exponential backoff for Bluetooth reconnection
 */
@AndroidEntryPoint
class TrackingForegroundService : Service() {

    @Inject
    lateinit var bluetoothGateway: BluetoothGateway

    @Inject
    lateinit var gpsGateway: GPSGateway

    @Inject
    lateinit var surveyEngine: SurveyEngine

    @Inject
    lateinit var surveyRepository: SurveyRepository

    @Inject
    lateinit var telemetryRepository: TelemetryRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dataProcessingJob: Job? = null
    private var reconnectionJob: Job? = null

    private var currentSessionId: Long = -1L
    private var packetCount = 0
    private var gpsUnavailableCount = 0

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "roadsense_tracking"
        private const val CHANNEL_NAME = "RoadSense Tracking"
        private const val RECONNECT_MAX_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY = 1000L // 1 second

        // Actions
        const val ACTION_START_SURVEY = "ACTION_START_SURVEY"
        const val ACTION_STOP_SURVEY = "ACTION_STOP_SURVEY"
        const val ACTION_PAUSE_SURVEY = "ACTION_PAUSE_SURVEY"
        const val ACTION_RESUME_SURVEY = "ACTION_RESUME_SURVEY"

        // Extras
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("🚀 TrackingForegroundService created")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing RoadSense..."))

        // Start GPS tracking immediately
        gpsGateway.startTracking()

        // Connect to ESP32 with auto-reconnect
        connectToESP32()

        // Start data processing pipeline
        startDataProcessing()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("📩 onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_SURVEY -> {
                val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                handleStartSurvey(sessionId)
            }

            ACTION_STOP_SURVEY -> handleStopSurvey()
            ACTION_PAUSE_SURVEY -> handlePauseSurvey()
            ACTION_RESUME_SURVEY -> handleResumeSurvey()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("🛑 TrackingForegroundService destroyed")

        dataProcessingJob?.cancel()
        reconnectionJob?.cancel()
        serviceScope.cancel()

        serviceScope.launch {
            bluetoothGateway.disconnect()
        }

        gpsGateway.stopTracking()
    }

    /**
     * Connect to ESP32 with exponential backoff retry
     */
    private fun connectToESP32() {
        reconnectionJob = serviceScope.launch {
            var attempts = 0

            while (attempts < RECONNECT_MAX_ATTEMPTS) {
                Timber.d("🔄 ESP32 connection attempt ${attempts + 1}/$RECONNECT_MAX_ATTEMPTS")

                if (bluetoothGateway.connect()) {
                    Timber.d("✅ ESP32 connected successfully")
                    attempts = 0 // Reset counter on success

                    // Wait for disconnection before retrying
                    while (true) {
                        bluetoothGateway.connectionState.collect { state ->
                            if (state is BluetoothGateway.ConnectionState.Disconnected) {
                                Timber.w("⚠️ ESP32 disconnected, retrying...")
                                delay(RECONNECT_BASE_DELAY)
                                bluetoothGateway.connect()
                            }
                        }
                    }

                } else {
                    attempts++
                    val delayMs = RECONNECT_BASE_DELAY * (1 shl attempts) // Exponential backoff
                    Timber.w("❌ Connection failed, retrying in ${delayMs}ms")

                    if (attempts < RECONNECT_MAX_ATTEMPTS) {
                        delay(delayMs)
                    } else {
                        Timber.e("❌ Max reconnection attempts reached")
                        updateNotification("ESP32 Connection Failed")
                    }
                }
            }
        }
    }

    /**
     * Start processing incoming data from ESP32 and GPS
     *
     * CRITICAL FLOW:
     * 1. Receive ESP32 packet → Parse to ESP32SensorData
     * 2. Fuse with GPS location (if available)
     * 3. Add quality flags (GPS_UNAVAILABLE, etc)
     * 4. Save to TelemetryRaw table (audit trail)
     * 5. Update real-time UI state
     * 6. Update notification
     */
    private fun startDataProcessing() {
        dataProcessingJob = serviceScope.launch {
            combine(
                bluetoothGateway.incomingData, surveyEngine.surveyState, gpsGateway.currentLocation
            ) { rawData, surveyState, gpsLocation ->
                Triple(rawData, surveyState, gpsLocation)
            }.collect { (rawData, surveyState, gpsLocation) ->

                // Only process when survey is running
                if (surveyState !is SurveyEngine.SurveyState.Running) {
                    return@collect
                }

                if (rawData.isEmpty()) {
                    return@collect
                }

                // Parse ESP32 data (NO CONVERSION - firmware sends final values)
                val sensorData = ESP32SensorData.fromBluetoothPacket(rawData)

                if (sensorData == null) {
                    Timber.w("⚠️ Failed to parse ESP32 packet: $rawData")
                    return@collect
                }

                packetCount++

                // Track GPS availability
                if (gpsLocation == null) {
                    gpsUnavailableCount++
                }

                // Build quality flags for transparency
                val qualityFlags = buildQualityFlags(sensorData, gpsLocation)

                // Create telemetry record with all data
                val telemetryRaw = TelemetryRaw(
                    sessionId = currentSessionId,
                    timestamp = sensorData.parsedAt,

                    // ✅ Sensor data (PRIMARY SOURCE)
                    speed = sensorData.currentSpeed,
                    vibrationZ = sensorData.accelZ,
                    hallSensorDistance = sensorData.tripDistanceMeters,
                    pulseCount = null, // Not sent in RS2 format

                    // ✅ GPS data (POSITION REFERENCE ONLY)
                    latitude = gpsLocation?.latitude,
                    longitude = gpsLocation?.longitude,
                    altitude = gpsLocation?.altitude,
                    gpsAccuracy = gpsLocation?.accuracy,

                    // ✅ Quality metrics
                    quality = calculateQuality(sensorData, gpsLocation),
                    flags = qualityFlags.joinToString(","),

                    // ✅ Additional metadata
                    temperature = sensorData.temperature,
                    humidity = null
                )

                // Save to database (audit trail)
                try {
                    telemetryRepository.insertTelemetryRaw(telemetryRaw)

                    // Update real-time display
                    telemetryRepository.updateLatestTelemetry(telemetryRaw)

                    // Update notification
                    updateNotification(sensorData, gpsLocation)

                    Timber.v("💾 Telemetry #$packetCount saved: ${sensorData.tripDistanceMeters}m, ${sensorData.currentSpeed}km/h")

                } catch (e: Exception) {
                    Timber.e(e, "❌ Failed to save telemetry")
                }
            }
        }
    }

    /**
     * Build quality flags for transparency and audit
     */
    private fun buildQualityFlags(
        sensorData: ESP32SensorData, gpsLocation: android.location.Location?
    ): List<String> {
        val flags = mutableListOf<String>()

        // GPS status
        if (gpsLocation == null) {
            flags.add(QualityFlag.GPS_UNAVAILABLE.name)
        } else if (gpsLocation.accuracy > 20f) {
            flags.add("GPS_POOR_ACCURACY")
        }

        // Speed validation
        if (sensorData.currentSpeed < 1f) {
            flags.add("VEHICLE_STOPPED")
        } else if (sensorData.currentSpeed > 60f) {
            flags.add(QualityFlag.SPEED_TOO_HIGH.name)
        }

        // Vibration validation
        if (kotlin.math.abs(sensorData.accelZ) > 2.5f) {
            flags.add(QualityFlag.VIBRATION_SPIKE.name)
        }

        sensorData.batteryVoltage?.let { voltage ->
            val flag = when {
                voltage < 3.4f -> "BATTERY_CRITICAL"
                voltage < 3.6f -> "BATTERY_LOW"
                else -> null
            }

            flag?.let { flags.add(it) }
        }



        // Packet errors
        sensorData.errorCount?.let { errors ->
            if (errors > 0) {
                flags.add("CRC_ERRORS:$errors")
            }
        }

        return flags
    }

    /**
     * Calculate quality score (HIGH/MEDIUM/LOW)
     */
    private fun calculateQuality(
        sensorData: ESP32SensorData, gpsLocation: android.location.Location?
    ): String {
        val score = sensorData.calculateQualityScore()
        val gpsGood = gpsLocation != null && gpsLocation.accuracy < 10f

        return when {
            score > 0.8f && gpsGood -> "HIGH"
            score > 0.5f -> "MEDIUM"
            else -> "LOW"
        }
    }

    /**
     * Handle start survey command
     */
    private fun handleStartSurvey(sessionId: Long) {
        Timber.d("▶️ Starting survey with session ID: $sessionId")
        currentSessionId = sessionId
        packetCount = 0
        gpsUnavailableCount = 0

        serviceScope.launch {
            // Send START command to ESP32
            bluetoothGateway.sendCommand("CMD:START")
            delay(100)

            // Request status to verify
            bluetoothGateway.sendCommand("CMD:STATUS")
        }
    }

    /**
     * Handle stop survey command
     */
    private fun handleStopSurvey() {
        Timber.d("⏹️ Stopping survey...")

        serviceScope.launch {
            // Send STOP command to ESP32
            bluetoothGateway.sendCommand("CMD:STOP")

            // Log statistics
            val gpsAvailability = if (packetCount > 0) {
                ((packetCount - gpsUnavailableCount) * 100f / packetCount)
            } else {
                0f
            }


            Timber.i(
                "📊 Survey stopped: $packetCount packets, GPS availability: ${
                    "%.1f".format(
                        gpsAvailability
                    )
                }%"
            )

            currentSessionId = -1L
        }
    }

    /**
     * Handle pause survey command
     */
    private fun handlePauseSurvey() {
        Timber.d("⏸️ Pausing survey...")
        serviceScope.launch {
            bluetoothGateway.sendCommand("CMD:PAUSE")
        }
    }

    /**
     * Handle resume survey command
     */
    private fun handleResumeSurvey() {
        Timber.d("▶️ Resuming survey...")
        serviceScope.launch {
            bluetoothGateway.sendCommand("CMD:RESUME")
        }
    }

    /**
     * Create notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "RoadSense survey tracking status"
                setShowBadge(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create notification
     */
    private fun createNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RoadSense Survey Active").setContentText(message)
            .setSmallIcon(R.drawable.ic_road_24).setContentIntent(pendingIntent).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }

    /**
     * Update notification with current data
     */
    private fun updateNotification(
        sensorData: ESP32SensorData, gpsLocation: android.location.Location?
    ) {
        val distance = "%.2f km".format(sensorData.tripDistanceMeters / 1000f)
        val speed = "%.1f km/h".format(sensorData.currentSpeed)
        val gpsStatus = if (gpsLocation != null) {
            "GPS ±${gpsLocation.accuracy.toInt()}m"
        } else {
            "GPS N/A"
        }

        val message = "$distance • $speed • $gpsStatus"

        val notification = createNotification(message)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Update notification with simple message
     */
    private fun updateNotification(message: String) {
        val notification = createNotification(message)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}



