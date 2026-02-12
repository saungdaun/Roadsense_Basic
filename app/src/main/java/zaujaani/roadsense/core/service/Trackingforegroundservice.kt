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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import zaujaani.roadsense.MainActivity
import zaujaani.roadsense.R
import zaujaani.roadsense.core.bluetooth.BluetoothGateway
import zaujaani.roadsense.core.gps.GPSGateway
import zaujaani.roadsense.data.local.TelemetryRaw
import zaujaani.roadsense.data.repository.TelemetryRepository
import zaujaani.roadsense.domain.engine.SurveyEngine
import zaujaani.roadsense.domain.model.ESP32SensorData
import zaujaani.roadsense.domain.model.QualityFlag
import javax.inject.Inject

/**
 * Tracking Foreground Service – The Heart of RoadSense
 *
 * 🔁 Auto‑reconnect ESP32 **sepenuhnya dikelola oleh BluetoothGateway**
 * 📡 Pipeline data trigger dari sensor, ambil GPS terakhir via StateFlow.value
 * 💾 Semua data disimpan dengan quality flags & sessionId nullable (cegah corrupt)
 * 🔔 Notifikasi persist dengan time‑based throttle (3 detik)
 *
 * ⚠️ WAJIB: Tambahkan di AndroidManifest.xml:
 * <service
 *     android:name=".core.service.TrackingForegroundService"
 *     android:foregroundServiceType="location|connectedDevice"
 *     android:exported="false" />
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
    lateinit var telemetryRepository: TelemetryRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dataProcessingJob: Job? = null

    // 🔐 Nullable session ID – mencegah penyimpanan ke database jika tidak valid
    private var currentSessionId: Long? = null

    private var packetCount = 0
    private var gpsUnavailableCount = 0

    // 🔔 Throttle notifikasi berbasis waktu
    private var lastNotificationTime = 0L
    private val NOTIFICATION_THROTTLE_MS = 3000L

    // 📦 Cache NotificationManager
    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "roadsense_tracking"
        private const val CHANNEL_NAME = "RoadSense Tracking"

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

        gpsGateway.startTracking()
        startDataProcessing()
        observeConnectionState() // ✅ hanya untuk logging/UI, tidak memicu reconnect
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("📩 onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_SURVEY -> {
                val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                if (sessionId != -1L) {
                    handleStartSurvey(sessionId)
                } else {
                    Timber.e("❌ Received ACTION_START_SURVEY with invalid sessionId")
                }
            }
            ACTION_STOP_SURVEY  -> handleStopSurvey()
            ACTION_PAUSE_SURVEY -> handlePauseSurvey()
            ACTION_RESUME_SURVEY-> handleResumeSurvey()
        }

        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("🛑 TrackingForegroundService destroyed")

        dataProcessingJob?.cancel()

        // 🔌 Putuskan koneksi Bluetooth dengan runBlocking(Dispatchers.IO)
        // karena disconnect() sekarang suspend dan operasi I/O cepat.
        runBlocking(Dispatchers.IO) {
            bluetoothGateway.disconnect()
        }

        gpsGateway.stopTracking()
        serviceScope.cancel()
    }

    // -------------------------------------------------------------------------
    //  🔍 OBSERVE CONNECTION STATE (HANYA UNTUK LOGGING / NOTIFIKASI)
    // -------------------------------------------------------------------------

    private fun observeConnectionState() {
        serviceScope.launch {
            bluetoothGateway.connectionState.collectLatest { state ->
                when (state) {
                    is BluetoothGateway.ConnectionState.Connected -> {
                        Timber.i("🔵 ESP32 connected")
                        updateNotification("ESP32 connected")
                    }
                    is BluetoothGateway.ConnectionState.Connecting -> {
                        Timber.d("🔵 Connecting to ESP32...")
                        updateNotification("Connecting to ESP32...")
                    }
                    is BluetoothGateway.ConnectionState.Disconnected -> {
                        Timber.w("🔴 ESP32 disconnected")
                        updateNotification("ESP32 disconnected")
                    }
                    is BluetoothGateway.ConnectionState.Reconnecting -> {
                        Timber.d("🔄 Reconnecting (${state.attempt}/${state.maxAttempts})")
                        updateNotification("Reconnecting (${state.attempt}/${state.maxAttempts})...")
                    }
                    is BluetoothGateway.ConnectionState.Error -> {
                        Timber.e("❌ Bluetooth error: ${state.message}")
                        updateNotification("Error: ${state.message}")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    //  📡 DATA PROCESSING PIPELINE (TIDAK PERNAH CRASH)
    // -------------------------------------------------------------------------

    private fun startDataProcessing() {
        dataProcessingJob = serviceScope.launch {
            bluetoothGateway.incomingData.collect { rawData ->
                try {
                    // 🛡️ Guard: survey harus running
                    val surveyState = surveyEngine.surveyState.value
                    if (surveyState !is SurveyEngine.SurveyState.Running) return@collect

                    // 🛡️ Guard: data tidak boleh kosong
                    if (rawData.isEmpty()) return@collect

                    // 🛡️ Guard: sessionId harus valid
                    val sessionId = currentSessionId ?: run {
                        Timber.w("⚠️ Survey running but sessionId is null – skipping packet")
                        return@collect
                    }

                    // Parse data ESP32
                    val sensorData = ESP32SensorData.fromBluetoothPacket(rawData)
                        ?: run {
                            Timber.w("⚠️ Gagal parse packet: $rawData")
                            return@collect
                        }

                    packetCount++
                    val gpsLocation = gpsGateway.currentLocation.value
                    if (gpsLocation == null) gpsUnavailableCount++

                    // Bangun record dan simpan
                    val telemetryRaw = buildTelemetryRecord(sessionId, sensorData, gpsLocation)
                    telemetryRepository.insertTelemetryRaw(telemetryRaw)

                    // 🔔 Time‑based notification throttle
                    val now = System.currentTimeMillis()
                    if (now - lastNotificationTime >= NOTIFICATION_THROTTLE_MS) {
                        updateNotification(sensorData, gpsLocation)
                        lastNotificationTime = now
                    }

                    Timber.v("💾 Telemetry #$packetCount saved: %.2fm, %.1fkm/h"
                        .format(sensorData.tripDistanceMeters, sensorData.currentSpeed))

                } catch (e: Exception) {
                    // 🛡️ Jangan biarkan flow mati – log dan lanjutkan
                    Timber.e(e, "❌ Unhandled exception in data processing pipeline")
                }
            }
        }
    }

    /**
     * Membuat objek TelemetryRaw dari data sensor + GPS.
     */
    private fun buildTelemetryRecord(
        sessionId: Long,
        sensorData: ESP32SensorData,
        gpsLocation: android.location.Location?
    ): TelemetryRaw {
        val qualityFlags = buildQualityFlags(sensorData, gpsLocation)

        return TelemetryRaw(
            sessionId = sessionId,
            timestamp = sensorData.parsedAt,
            speed = sensorData.currentSpeed,
            vibrationZ = sensorData.accelZ,
            hallSensorDistance = sensorData.tripDistanceMeters,
            pulseCount = null,
            latitude = gpsLocation?.latitude,
            longitude = gpsLocation?.longitude,
            altitude = gpsLocation?.altitude,
            gpsAccuracy = gpsLocation?.accuracy,
            quality = calculateQuality(sensorData, gpsLocation),
            flags = qualityFlags.joinToString(","),
            temperature = sensorData.temperature,
            humidity = null
        )
    }

    private fun buildQualityFlags(
        sensorData: ESP32SensorData,
        gpsLocation: android.location.Location?
    ): List<String> = buildList {

        // ---- GPS ----
        if (gpsLocation == null) {
            add(QualityFlag.GPS_UNAVAILABLE.name)
        } else if (gpsLocation.accuracy > 20f) {
            add("GPS_POOR_ACCURACY")
        }

        // ---- Speed ----
        val speed = sensorData.currentSpeed
        if (speed < 1f) {
            add("VEHICLE_STOPPED")
        } else if (speed > 60f) {
            add(QualityFlag.SPEED_TOO_HIGH.name)
        }

        // ---- Vibration ----
        if (kotlin.math.abs(sensorData.accelZ) > 2.5f) {
            add(QualityFlag.VIBRATION_SPIKE.name)
        }

        // ---- Battery ----
        sensorData.batteryVoltage?.let { voltage ->
            when {
                voltage < 3.4f -> add("BATTERY_CRITICAL")
                voltage < 3.6f -> add("BATTERY_LOW")
                else -> Unit
            }
        }

        // ---- CRC Errors ----
        sensorData.errorCount
            ?.takeIf { it > 0 }
            ?.let { add("CRC_ERRORS:$it") }
    }

    private fun calculateQuality(
        sensorData: ESP32SensorData,
        gpsLocation: android.location.Location?
    ): String {

        val sensorScore = sensorData.calculateQualityScore()

        val gpsGood = gpsLocation?.accuracy?.let { it < 10f } == true

        return when {
            sensorScore > 0.8f && gpsGood -> "HIGH"
            sensorScore > 0.5f -> "MEDIUM"
            else -> "LOW"
        }
    }

    // -------------------------------------------------------------------------
    //  🎮 SURVEY CONTROL
    // -------------------------------------------------------------------------

    private fun handleStartSurvey(sessionId: Long) {
        Timber.d("▶️ Start survey: $sessionId")
        currentSessionId = sessionId
        packetCount = 0
        gpsUnavailableCount = 0
        lastNotificationTime = 0L // reset throttle

        serviceScope.launch {
            bluetoothGateway.sendCommand("CMD:START")
            delay(100)
            bluetoothGateway.sendCommand("CMD:STATUS")
        }
    }

    private fun handleStopSurvey() {
        Timber.d("⏹️ Stop survey")
        serviceScope.launch {
            bluetoothGateway.sendCommand("CMD:STOP")
            val gpsAvailability = if (packetCount > 0) {
                (packetCount - gpsUnavailableCount) * 100f / packetCount
            } else 0f
            Timber.i("📊 Survey stopped: %d packets, GPS availability: %.1f%%",
                packetCount, gpsAvailability)
            currentSessionId = null
        }
    }

    private fun handlePauseSurvey() {
        Timber.d("⏸️ Pause survey")
        serviceScope.launch { bluetoothGateway.sendCommand("CMD:PAUSE") }
    }

    private fun handleResumeSurvey() {
        Timber.d("▶️ Resume survey")
        serviceScope.launch { bluetoothGateway.sendCommand("CMD:RESUME") }
    }

    // -------------------------------------------------------------------------
    //  🔔 NOTIFICATION (DENGAN CACHE MANAGER)
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "RoadSense survey tracking status"
                setShowBadge(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RoadSense Survey Active")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_road_24)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(sensorData: ESP32SensorData, gpsLocation: android.location.Location?) {
        val distance = "%.2f km".format(sensorData.tripDistanceMeters / 1000f)
        val speed = "%.1f km/h".format(sensorData.currentSpeed)
        val gpsStatus = gpsLocation?.let {
            "GPS ±${it.accuracy.toInt()}m"
        } ?: "GPS N/A"

        val message = "$distance • $speed • $gpsStatus"
        updateNotification(message)
    }

    private fun updateNotification(message: String) {
        val notification = createNotification(message)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}