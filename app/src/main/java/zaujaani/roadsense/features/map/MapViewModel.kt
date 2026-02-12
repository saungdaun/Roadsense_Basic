package zaujaani.roadsense.features.map

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import timber.log.Timber
import zaujaani.roadsense.core.bluetooth.BluetoothGateway
import zaujaani.roadsense.core.events.RealtimeRoadsenseBus
import zaujaani.roadsense.core.gps.GPSGateway
import zaujaani.roadsense.data.local.*
import zaujaani.roadsense.data.repository.SurveyRepository
import zaujaani.roadsense.data.repository.TelemetryRepository
import zaujaani.roadsense.domain.engine.QualityScoreCalculator
import zaujaani.roadsense.domain.engine.SurveyEngine
import zaujaani.roadsense.domain.model.*
import zaujaani.roadsense.domain.usecase.ZAxisValidation
import zaujaani.roadsense.domain.usecase.ZAxisValidationHelper
import javax.inject.Inject

/**
 * MapViewModel - Main survey screen state management
 *
 * PRINCIPLES:
 * ✅ Sensor is PRIMARY distance source (from ESP32)
 * ✅ GPS is POSITION REFERENCE only
 * ✅ Never stop survey on GPS drop
 * ✅ Clean separation: UI State vs Business Logic
 * ✅ All data saved with quality flags for audit trail
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val surveyEngine: SurveyEngine,
    private val bus: RealtimeRoadsenseBus,
    private val bluetoothGateway: BluetoothGateway,
    private val surveyRepository: SurveyRepository,
    private val telemetryRepository: TelemetryRepository,
    private val qualityScoreCalculator: QualityScoreCalculator,
    private val zAxisValidationHelper: ZAxisValidationHelper   // ✅ Inject helper baru
) : ViewModel() {

    // ========== UI STATE ==========
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    // ========== TRACKING DATA ==========
    private val _trackingPoints = MutableStateFlow<List<GeoPoint>>(emptyList())
    val trackingPoints: StateFlow<List<GeoPoint>> = _trackingPoints.asStateFlow()

    private val _segmentCreationPoints = MutableStateFlow<List<GeoPoint>>(emptyList())
    val segmentCreationPoints: StateFlow<List<GeoPoint>> = _segmentCreationPoints.asStateFlow()

    private val _segments = MutableStateFlow<List<RoadSegment>>(emptyList())
    val segments: StateFlow<List<RoadSegment>> = _segments.asStateFlow()

    // ========== DEVICE STATUS ==========
    private val _deviceReadyState = MutableStateFlow<DeviceReadyState>(DeviceReadyState.NOT_CONNECTED)
    val deviceReadyState: StateFlow<DeviceReadyState> = _deviceReadyState.asStateFlow()

    // ========== INTERNAL STATE ==========
    private var currentSessionId: Long = -1L
    private var latestSensorData: ESP32SensorData? = null
    private var latestZAxisValidation: ZAxisValidation? = null   // ✅ Simpan hasil validasi

    // ========== INITIALIZATION ==========
    init {
        Timber.d("🎯 MapViewModel initialized")
        observeBus()
        observeSurveyState()
        loadSavedSegments()
        checkDeviceReadyState()
    }

    // ========== OBSERVERS ==========

    /**
     * Subscribe ke RealtimeRoadsenseBus untuk update sensor & GPS
     */
    private fun observeBus() {
        viewModelScope.launch {
            bus.sensorData
                .filterNotNull()
                .collect { sensorData ->
                    // Store latest data
                    latestSensorData = sensorData

                    // ✅ Validasi Z-Axis dengan helper
                    val validation = zAxisValidationHelper.validate(
                        accelZ = sensorData.accelZ,
                        speed = sensorData.currentSpeed
                    )
                    latestZAxisValidation = validation

                    // ✅ Mapping ke Confidence enum untuk UI
                    val confidence = when (validation) {
                        is ZAxisValidation.ValidMoving -> when {
                            validation.confidence > 0.7f -> Confidence.HIGH
                            validation.confidence > 0.4f -> Confidence.MEDIUM
                            else -> Confidence.LOW
                        }
                        else -> Confidence.LOW
                    }

                    // Update UI state
                    _uiState.update {
                        it.copy(
                            currentSpeed = sensorData.currentSpeed,
                            tripDistance = sensorData.tripDistanceMeters,
                            currentVibration = sensorData.accelZ,
                            packetCount = it.packetCount + 1,
                            batteryVoltage = sensorData.batteryVoltage,
                            temperature = sensorData.temperature,
                            zAxisConfidence = confidence,
                            validationColor = validation.color.name
                        )
                    }

                    Timber.v("📊 Sensor: ${sensorData.tripDistanceMeters}m, ${sensorData.currentSpeed}km/h, Z=${sensorData.accelZ}, Validation=${validation::class.simpleName}")
                }
        }

        viewModelScope.launch {
            bus.gpsLocation.collect { location ->
                _uiState.update {
                    it.copy(
                        currentLocation = location,
                        gpsAccuracy = location?.accuracy?.let { acc -> "±${acc.toInt()}m" } ?: "No GPS"
                    )
                }

                // Update GPS signal strength
                val strength = when {
                    location == null -> SignalStrength.NONE
                    location.accuracy < 10f -> SignalStrength.EXCELLENT
                    location.accuracy < 20f -> SignalStrength.GOOD
                    location.accuracy < 50f -> SignalStrength.FAIR
                    else -> SignalStrength.POOR
                }
                _uiState.update { it.copy(gpsSignalStrength = strength) }
            }
        }
    }

    /**
     * Monitor survey state dari bus
     */
    private fun observeSurveyState() {
        viewModelScope.launch {
            bus.surveyState.collect { state ->
                _uiState.update {
                    it.copy(
                        isTracking = state is SurveyEngine.SurveyState.Running,
                        isPaused = state is SurveyEngine.SurveyState.Paused
                    )
                }

                when (state) {
                    is SurveyEngine.SurveyState.Running -> {
                        currentSessionId = state.surveyId
                        collectTrackingPoints(state.surveyId)
                    }
                    is SurveyEngine.SurveyState.Stopped -> {
                        currentSessionId = -1L
                        _trackingPoints.value = emptyList()
                        latestSensorData = null
                        latestZAxisValidation = null
                        zAxisValidationHelper.reset()   // ✅ Reset helper saat survey berhenti
                    }
                    else -> {
                        // Paused or Idle - keep session ID
                    }
                }
            }
        }
    }

    /**
     * Monitor Bluetooth connection status
     */
    private fun observeBluetooth() {
        viewModelScope.launch {
            bluetoothGateway.connectionState.collect { state ->
                val connected = state is BluetoothGateway.ConnectionState.Connected
                _uiState.update { it.copy(esp32Connected = connected) }
                updateDeviceReadyState()
            }
        }
    }

    /**
     * Collect GPS tracking points for map display
     */
    private fun collectTrackingPoints(sessionId: Long) {
        viewModelScope.launch {
            try {
                telemetryRepository.getTelemetryRawBySession(sessionId)
                    .collect { telemetryList ->
                        val points = telemetryList
                            .filter { it.latitude != null && it.longitude != null }
                            .map { GeoPoint(it.latitude!!, it.longitude!!) }
                        _trackingPoints.value = points
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load tracking points")
            }
        }
    }

    /**
     * Load saved road segments for map display
     */
    private fun loadSavedSegments() {
        viewModelScope.launch {
            try {
                surveyRepository.getAllRoadSegments()
                    .collect { segments ->
                        _segments.value = segments
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load segments")
            }
        }
    }

    // ========== PUBLIC API FOR FRAGMENT ==========

    /**
     * Check if device is ready to start survey
     */
    fun checkDeviceReadyState() {
        viewModelScope.launch {
            updateDeviceReadyState()
        }
    }

    private suspend fun updateDeviceReadyState() {
        val connected = bluetoothGateway.isConnected()
        val hasCalib = surveyRepository.hasCalibration()

        _deviceReadyState.value = when {
            !connected -> DeviceReadyState.NOT_CONNECTED
            !hasCalib -> DeviceReadyState.CALIBRATION_NEEDED
            else -> DeviceReadyState.READY
        }
    }

    /**
     * Start survey session
     */
    suspend fun startSurvey(): SurveyStartResult {
        return try {
            if (_deviceReadyState.value != DeviceReadyState.READY) {
                return SurveyStartResult.ERROR("Device not ready: ${_deviceReadyState.value}")
            }

            val sessionIdStr = surveyEngine.generateSessionId()
            val calibration = surveyRepository.getActiveCalibration()

            val session = SurveySession(
                surveyorName = "User",
                startTime = System.currentTimeMillis(),
                deviceName = android.os.Build.MODEL,
                calibrationId = calibration?.id,
                notes = "Session: $sessionIdStr"
            )

            val newSessionId = surveyRepository.createSurveySession(session)
            currentSessionId = newSessionId

            surveyEngine.startSurvey(sessionIdStr, newSessionId)
            bluetoothGateway.sendCommand("CMD:START")

            // ✅ Reset Z-Axis validation history untuk survey baru
            zAxisValidationHelper.reset()

            Timber.i("✅ Survey started: Session ID = $newSessionId")
            SurveyStartResult.SUCCESS

        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to start survey")
            SurveyStartResult.ERROR(e.message ?: "Unknown error")
        }
    }

    /**
     * Stop survey session
     */
    suspend fun stopSurvey() {
        try {
            surveyEngine.stopSurvey()
            bluetoothGateway.sendCommand("CMD:STOP")

            if (currentSessionId != -1L) {
                surveyRepository.updateSurveySessionStatus(
                    currentSessionId,
                    "COMPLETED",
                    System.currentTimeMillis()
                )
            }

            currentSessionId = -1L
            latestSensorData = null
            latestZAxisValidation = null
            _trackingPoints.value = emptyList()

            _uiState.update {
                it.copy(
                    isTracking = false,
                    isPaused = false,
                    tripDistance = 0f,
                    currentSpeed = 0f,
                    currentVibration = 0f,
                    packetCount = 0,
                    errorCount = 0,
                    zAxisConfidence = Confidence.LOW,
                    validationColor = ""
                )
            }

            Timber.i("✅ Survey stopped")

        } catch (e: Exception) {
            Timber.e(e, "Error stopping survey")
        }
    }

    /**
     * Pause survey
     */
    fun pauseSurvey() {
        surveyEngine.pauseSurvey()
        viewModelScope.launch {
            bluetoothGateway.sendCommand("CMD:PAUSE")
            if (currentSessionId != -1L) {
                surveyRepository.updateSurveySessionStatus(currentSessionId, "PAUSED")
            }
        }
    }

    /**
     * Resume survey
     */
    fun resumeSurvey() {
        surveyEngine.resumeSurvey()
        viewModelScope.launch {
            bluetoothGateway.sendCommand("CMD:RESUME")
            if (currentSessionId != -1L) {
                surveyRepository.updateSurveySessionStatus(currentSessionId, "ACTIVE")
            }
        }
    }

    // ========== SEGMENT CREATION ==========

    /**
     * Start creating a road segment
     */
    fun startSegmentCreation(): Boolean {
        if (!surveyEngine.isRunning() || surveyEngine.isPaused()) {
            return false
        }
        _uiState.update { it.copy(isCreatingSegment = true) }
        _segmentCreationPoints.value = emptyList()
        return true
    }

    /**
     * Set segment start point
     */
    fun setSegmentStartPoint(point: GeoPoint) {
        _segmentCreationPoints.value = listOf(point)
    }

    /**
     * Set segment end point
     */
    fun setSegmentEndPoint(point: GeoPoint) {
        val current = _segmentCreationPoints.value.toMutableList()
        if (current.isNotEmpty()) {
            current.add(point)
            _segmentCreationPoints.value = current
        }
    }

    /**
     * Validate segment before saving - menggunakan ZAxisValidationHelper
     */
    fun completeSegmentCreation(): ZAxisValidationResult {
        val points = _segmentCreationPoints.value
        if (points.size != 2) {
            return ZAxisValidationResult(
                isValid = false,
                messages = listOf("Butuh titik awal dan akhir"),
                confidence = Confidence.LOW
            )
        }

        val validation = latestZAxisValidation
        val sensorData = latestSensorData
        val state = _uiState.value

        return when (validation) {
            null -> ZAxisValidationResult(
                isValid = false,
                messages = listOf("Belum ada data sensor"),
                confidence = Confidence.LOW
            )
            is ZAxisValidation.ValidMoving -> {
                val confidence = when {
                    validation.confidence > 0.7f -> Confidence.HIGH
                    validation.confidence > 0.4f -> Confidence.MEDIUM
                    else -> Confidence.LOW
                }

                val messages = mutableListOf<String>()
                if (sensorData?.currentSpeed ?: 0f >= 20f) {
                    messages.add("⚠️ Kecepatan > 20 km/h (rekomendasi < 20)")
                }
                if (state.currentLocation?.accuracy ?: 999f >= 5f) {
                    messages.add("⚠️ Akurasi GPS > 5m (rekomendasi < 5m)")
                }
                if (kotlin.math.abs(sensorData?.accelZ ?: 0f) > 1.5f) {
                    messages.add("⚠️ Getaran tinggi terdeteksi")
                }
                if (state.currentLocation == null) {
                    messages.add("⚠️ GPS tidak tersedia")
                }

                ZAxisValidationResult(
                    isValid = true,
                    messages = messages,
                    confidence = confidence
                )
            }
            is ZAxisValidation.WarningStopped -> ZAxisValidationResult(
                isValid = false,
                messages = listOf(validation.message),
                confidence = Confidence.LOW
            )
            is ZAxisValidation.InvalidShake -> ZAxisValidationResult(
                isValid = false,
                messages = listOf(validation.message),
                confidence = Confidence.LOW
            )
            is ZAxisValidation.InvalidNoMovement -> ZAxisValidationResult(
                isValid = false,
                messages = listOf(validation.message),
                confidence = Confidence.LOW
            )
            is ZAxisValidation.SuspiciousPattern -> ZAxisValidationResult(
                isValid = false,
                messages = listOf(validation.message),
                confidence = Confidence.LOW
            )
            // 🔴 BRANCH ELSE WAJIB – antisipasi subclass baru
            else -> {
                Timber.w("⚠️ Unknown ZAxisValidation type: ${validation::class.simpleName}")
                ZAxisValidationResult(
                    isValid = false,
                    messages = listOf("Status validasi tidak dikenal"),
                    confidence = Confidence.LOW
                )
            }
        }
    }

    /**
     * Cancel segment creation
     */
    fun cancelSegmentCreation() {
        _uiState.update { it.copy(isCreatingSegment = false) }
        _segmentCreationPoints.value = emptyList()
    }

    /**
     * Save road segment to database
     */
    suspend fun saveSegment(
        roadName: String,
        conditionCode: String,
        surfaceCode: String,
        notes: String?
    ) {
        val points = _segmentCreationPoints.value
        if (points.size != 2) {
            Timber.w("Cannot save segment: need 2 points")
            return
        }

        val start = points[0]
        val end = points[1]
        val state = _uiState.value
        val sensorData = latestSensorData ?: return

        // Calculate quality score
        val qualityScore = qualityScoreCalculator.calculateQualityScore(
            avgIRI = 0.0,
            dataPointCount = state.packetCount,
            gpsAvailabilityPercent = if (state.currentLocation != null) 100.0 else 0.0,
            hasOutliers = false
        ).toFloat() / 100f

        // Build quality flags
        val flags = buildQualityFlags(sensorData, state.currentLocation)

        val segment = RoadSegment(
            sessionId = currentSessionId,
            roadName = roadName,
            startLatitude = start.latitude,
            startLongitude = start.longitude,
            endLatitude = end.latitude,
            endLongitude = end.longitude,
            distanceMeters = sensorData.tripDistanceMeters,
            condition = conditionCode,
            surface = surfaceCode,
            confidence = state.zAxisConfidence.name,
            avgSpeed = sensorData.currentSpeed,
            avgAccuracy = state.currentLocation?.accuracy ?: 0f,
            avgVibration = sensorData.accelZ,
            qualityScore = qualityScore,
            timestamp = System.currentTimeMillis(),
            surveyorId = "User",
            notes = notes,
            severity = calculateSeverity(conditionCode, sensorData.tripDistanceMeters),
            dataSource = "SENSOR_PRIMARY",
            hallSensorDistance = sensorData.tripDistanceMeters,
            gpsDistance = null,
            flags = flags.joinToString(",")
        )

        surveyRepository.insertRoadSegment(segment)
        cancelSegmentCreation()
        loadSavedSegments()

        Timber.i("✅ Segment saved: $roadName, ${segment.distanceMeters}m")
    }

    /**
     * Build quality flags for segment
     */
    private fun buildQualityFlags(sensorData: ESP32SensorData, location: Location?): List<String> {
        val flags = mutableListOf<String>()

        if (location == null) {
            flags.add(QualityFlag.GPS_UNAVAILABLE.name)
        }

        if (sensorData.currentSpeed > 20f) {
            flags.add(QualityFlag.SPEED_TOO_HIGH.name)
        }

        if (kotlin.math.abs(sensorData.accelZ) > 1.5f) {
            flags.add(QualityFlag.VIBRATION_SPIKE.name)
        }

        if (_uiState.value.zAxisConfidence == Confidence.HIGH) {
            flags.add(QualityFlag.HIGH_CONFIDENCE.name)
        }

        sensorData.batteryVoltage?.let { voltage ->
            if (voltage < 3.6f) {
                flags.add("BATTERY_WARNING")
            }
        }

        return flags
    }

    /**
     * Calculate severity based on condition and distance
     */
    private fun calculateSeverity(conditionCode: String, distance: Float): Int {
        return when (conditionCode) {
            RoadCondition.GOOD.code -> 1
            RoadCondition.MODERATE.code -> if (distance > 50) 3 else 2
            RoadCondition.LIGHT_DAMAGE.code -> if (distance > 30) 5 else 4
            RoadCondition.HEAVY_DAMAGE.code -> if (distance > 20) 7 else 6
            else -> 0
        }
    }

    // ========== DATA CLASSES ==========

    data class MapUiState(
        val isTracking: Boolean = false,
        val isPaused: Boolean = false,
        val isCreatingSegment: Boolean = false,
        val currentSpeed: Float = 0f,
        val tripDistance: Float = 0f,
        val currentVibration: Float = 0f,
        val esp32Connected: Boolean = false,
        val packetCount: Int = 0,
        val errorCount: Int = 0,
        val currentLocation: Location? = null,
        val gpsAccuracy: String = "No GPS",
        val gpsSignalStrength: SignalStrength = SignalStrength.NONE,
        val zAxisConfidence: Confidence = Confidence.LOW,
        val batteryVoltage: Float? = null,
        val temperature: Float? = null,
        val validationColor: String = "",
        val zAxisValidation: ZAxisValidation? = null
    )

    enum class DeviceReadyState {
        READY,
        NOT_CONNECTED,
        CALIBRATION_NEEDED,
        GPS_DISABLED,
        BLUETOOTH_DISABLED
    }

    enum class SignalStrength {
        NONE,
        SEARCHING,
        POOR,
        FAIR,
        GOOD,
        EXCELLENT
    }

    sealed class SurveyStartResult {
        object SUCCESS : SurveyStartResult()
        data class ERROR(val message: String) : SurveyStartResult()
    }

    data class ZAxisValidationResult(
        val isValid: Boolean,
        val messages: List<String>,
        val confidence: Confidence
    )
}