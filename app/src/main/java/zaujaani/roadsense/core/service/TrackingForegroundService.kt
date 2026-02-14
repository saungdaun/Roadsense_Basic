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
import zaujaani.roadsense.core.constants.SurveyConstants
import zaujaani.roadsense.core.gps.GPSGateway
import zaujaani.roadsense.data.local.TelemetryRaw
import zaujaani.roadsense.data.local.buildQualityFlags
import zaujaani.roadsense.data.local.toJsonArray
import zaujaani.roadsense.data.local.toTelemetryRaw
import zaujaani.roadsense.data.repository.TelemetryRepository
import zaujaani.roadsense.domain.engine.SurveyEngine
import zaujaani.roadsense.domain.model.ESP32SensorData
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

    // 📦 Buffer untuk batch insert
    private val telemetryBuffer = mutableListOf<TelemetryRaw>()
    private var lastBatchTime = 0L
    private var lastNotificationTime = 0L

    // Statistik
    private var packetCount = 0
    private var gpsUnavailableCount = 0

    // 📦 Cache NotificationManager
    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    companion object {
        private const val NOTIFICATION_ID = SurveyConstants.NOTIFICATION_ID
        private const val CHANNEL_ID = SurveyConstants.NOTIFICATION_CHANNEL_ID
        private const val CHANNEL_NAME = SurveyConstants.NOTIFICATION_CHANNEL_NAME

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
        observeConnectionState()
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

        // Flush buffer sebelum mati
        flushTelemetryBuffer()

        dataProcessingJob?.cancel()

        // 🔌 Putuskan koneksi Bluetooth dengan runBlocking(Dispatchers.IO)
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

                    // Bangun flags dan record
                    val flags = buildQualityFlags(sensorData, gpsLocation)
                    val telemetryRaw = sensorData.toTelemetryRaw(sessionId, gpsLocation, flags)

                    // Tambahkan ke buffer
                    telemetryBuffer.add(telemetryRaw)

                    // 🔔 Throttle notifikasi
                    val now = System.currentTimeMillis()
                    if (now - lastNotificationTime >= SurveyConstants.NOTIFICATION_THROTTLE_MS) {
                        updateNotification(sensorData, gpsLocation)
                        lastNotificationTime = now
                    }

                    // 💾 Flush jika buffer penuh atau interval tercapai
                    if (telemetryBuffer.size >= SurveyConstants.TELEMETRY_BATCH_SIZE ||
                        now - lastBatchTime >= SurveyConstants.AUTO_SAVE_INTERVAL_MS) {
                        flushTelemetryBuffer()
                    }

                    Timber.v("💾 Telemetry #$packetCount buffered: %.2fm, %.1fkm/h"
                        .format(sensorData.tripDistanceMeters, sensorData.currentSpeed))

                } catch (e: Exception) {
                    // 🛡️ Jangan biarkan flow mati – log dan lanjutkan
                    Timber.e(e, "❌ Unhandled exception in data processing pipeline")
                }
            }
        }
    }

    /**
     * Menyimpan buffer telemetry ke database
     */
    private fun flushTelemetryBuffer() {
        if (telemetryBuffer.isNotEmpty()) {
            serviceScope.launch {
                try {
                    telemetryRepository.insertTelemetryRawBatch(telemetryBuffer.toList())
                    Timber.d("💾 Flushed ${telemetryBuffer.size} telemetry records")
                    telemetryBuffer.clear()
                    lastBatchTime = System.currentTimeMillis()
                } catch (e: Exception) {
                    Timber.e(e, "❌ Failed to insert telemetry batch")
                }
            }
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
        lastNotificationTime = 0L
        telemetryBuffer.clear() // bersihkan buffer lama

        serviceScope.launch {
            bluetoothGateway.sendCommand("CMD:START")
            delay(100)
            bluetoothGateway.sendCommand("CMD:STATUS")
        }
    }

    private fun handleStopSurvey() {
        Timber.d("⏹️ Stop survey")
        // Flush sebelum stop
        flushTelemetryBuffer()

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
        flushTelemetryBuffer() // simpan data sebelum pause
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