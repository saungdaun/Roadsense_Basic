package zaujaani.roadsense.features.map

import android.content.Context
import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import timber.log.Timber
import zaujaani.roadsense.core.bluetooth.BluetoothGateway
import zaujaani.roadsense.core.constants.SurveyConstants
import zaujaani.roadsense.core.events.RealtimeRoadsenseBus
import zaujaani.roadsense.core.gps.GPSGateway
import zaujaani.roadsense.core.maps.OfflineMapManager
import zaujaani.roadsense.data.local.*
import zaujaani.roadsense.data.repository.SurveyRepository
import zaujaani.roadsense.data.repository.TelemetryRepository
import zaujaani.roadsense.domain.engine.QualityScoreCalculator
import zaujaani.roadsense.domain.engine.SurveyEngine
import zaujaani.roadsense.domain.model.*
import zaujaani.roadsense.domain.usecase.ZAxisValidation
import zaujaani.roadsense.domain.usecase.ZAxisValidationHelper
import javax.inject.Inject

@HiltViewModel
class MapViewModel @Inject constructor(
    private val surveyEngine: SurveyEngine,
    private val bus: RealtimeRoadsenseBus,
    private val bluetoothGateway: BluetoothGateway,
    private val surveyRepository: SurveyRepository,
    private val telemetryRepository: TelemetryRepository,
    private val qualityScoreCalculator: QualityScoreCalculator,
    private val zAxisValidationHelper: ZAxisValidationHelper,
    private val offlineMapManager: OfflineMapManager
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
    private var latestZAxisValidation: ZAxisValidation? = null

    init {
        observeBus()
        observeSurveyState()
        observeBluetooth()
        observeTrackingPoints()
        loadSavedSegments()
        checkDeviceReadyState()
    }

    // ========== OBSERVERS ==========
    private fun observeBus() {
        viewModelScope.launch {
            bus.sensorData
                .filterNotNull()
                .collect { sensorData ->
                    latestSensorData = sensorData
                    val validation = zAxisValidationHelper.validate(
                        accelZ = sensorData.accelZ,
                        speed = sensorData.currentSpeed
                    )
                    latestZAxisValidation = validation

                    val confidence = when (validation) {
                        is ZAxisValidation.ValidMoving -> when {
                            validation.confidence > 0.7f -> Confidence.HIGH
                            validation.confidence > 0.4f -> Confidence.MEDIUM
                            else -> Confidence.LOW
                        }
                        else -> Confidence.LOW
                    }

                    _uiState.update {
                        it.copy(
                            currentSpeed = sensorData.currentSpeed,
                            tripDistance = sensorData.tripDistanceMeters,
                            currentVibration = sensorData.accelZ,
                            packetCount = it.packetCount + 1,
                            batteryVoltage = sensorData.batteryVoltage,
                            temperature = sensorData.temperature,
                            zAxisConfidence = confidence,
                            validationColor = validation.color.name,
                            zAxisValidation = validation
                        )
                    }
                }
        }

        viewModelScope.launch {
            bus.gpsLocation.collect { location ->
                _uiState.update {
                    it.copy(
                        currentLocation = location,
                        gpsAccuracy = location?.accuracy?.let { "±${it.toInt()}m" } ?: "No GPS"
                    )
                }

                val strength = when {
                    location == null -> SignalStrength.NONE
                    location.accuracy < 10f -> SignalStrength.EXCELLENT
                    location.accuracy < SurveyConstants.GPS_GOOD_ACCURACY_METERS -> SignalStrength.GOOD
                    location.accuracy < 50f -> SignalStrength.FAIR
                    else -> SignalStrength.POOR
                }
                _uiState.update { it.copy(gpsSignalStrength = strength) }
            }
        }
    }

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
                    is SurveyEngine.SurveyState.Running -> currentSessionId = state.surveyId
                    is SurveyEngine.SurveyState.Stopped -> {
                        currentSessionId = -1L
                        _trackingPoints.value = emptyList()
                        latestSensorData = null
                        latestZAxisValidation = null
                        zAxisValidationHelper.reset()
                        _uiState.update { it.copy(zAxisValidation = null) }
                    }
                    else -> { /* Paused or Idle */ }
                }
            }
        }
    }

    private fun observeBluetooth() {
        viewModelScope.launch {
            bluetoothGateway.connectionState.collect { state ->
                val connected = state is BluetoothGateway.ConnectionState.Connected
                _uiState.update { it.copy(esp32Connected = connected) }
                updateDeviceReadyState()
            }
        }
    }

    private fun observeTrackingPoints() {
        viewModelScope.launch {
            bus.surveyState
                .flatMapLatest { state ->
                    if (state is SurveyEngine.SurveyState.Running) {
                        telemetryRepository.getTelemetryRawBySession(state.surveyId)
                    } else {
                        flowOf(emptyList())
                    }
                }
                .collect { telemetryList ->
                    val points = telemetryList
                        .filter { it.latitude != null && it.longitude != null }
                        .map { GeoPoint(it.latitude!!, it.longitude!!) }
                    _trackingPoints.value = points
                }
        }
    }

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

    // ========== PUBLIC API ==========
    fun checkDeviceReadyState() {
        viewModelScope.launch { updateDeviceReadyState() }
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
            surveyEngine.isReady.first { it }

            bluetoothGateway.sendCommand("CMD:START")
            zAxisValidationHelper.reset()

            Timber.i("✅ Survey started: Session ID = $newSessionId")
            SurveyStartResult.SUCCESS
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to start survey")
            SurveyStartResult.ERROR(e.message ?: "Unknown error")
        }
    }

    suspend fun stopSurvey() {
        try {
            surveyEngine.stopSurvey()
            bluetoothGateway.sendCommand("CMD:STOP")
            if (currentSessionId != -1L) {
                surveyRepository.updateSurveySessionStatus(currentSessionId, "COMPLETED", System.currentTimeMillis())
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
                    validationColor = "",
                    zAxisValidation = null
                )
            }
            Timber.i("✅ Survey stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping survey")
        }
    }

    fun pauseSurvey() {
        surveyEngine.pauseSurvey()
        viewModelScope.launch {
            bluetoothGateway.sendCommand("CMD:PAUSE")
            if (currentSessionId != -1L) {
                surveyRepository.updateSurveySessionStatus(currentSessionId, "PAUSED")
            }
        }
    }

    fun resumeSurvey() {
        surveyEngine.resumeSurvey()
        viewModelScope.launch {
            bluetoothGateway.sendCommand("CMD:RESUME")
            if (currentSessionId != -1L) {
                surveyRepository.updateSurveySessionStatus(currentSessionId, "ACTIVE")
            }
        }
    }

    // ========== OFFLINE MAPS ==========
    fun downloadMapArea(context: Context, boundingBox: BoundingBox, zoomLevels: IntRange) {
        offlineMapManager.downloadMapArea(
            activityContext = context,
            boundingBox = boundingBox,
            zoomLevels = zoomLevels,
            coroutineScope = viewModelScope,
            onResult = { result ->
                result.onSuccess { count ->
                    _uiState.update { it.copy(downloadProgress = null, downloadMessage = "Download selesai: $count tiles") }
                    Timber.i("Download sukses: $count tiles")
                }.onFailure { error ->
                    _uiState.update { it.copy(downloadProgress = null, downloadMessage = "Gagal: ${error.message}") }
                    Timber.e(error, "Download gagal")
                }
            }
        )

        // Update progress via observing downloadState dari manager (bisa juga dilakukan)
        viewModelScope.launch {
            offlineMapManager.downloadState.collect { state ->
                when (state) {
                    is OfflineMapManager.DownloadState.Downloading -> {
                        _uiState.update {
                            it.copy(
                                downloadProgress = state.progress,
                                downloadMessage = "${state.current}/${state.total} tiles"
                            )
                        }
                    }
                    is OfflineMapManager.DownloadState.Preparing -> {
                        _uiState.update { it.copy(downloadProgress = 0f, downloadMessage = state.message) }
                    }
                    is OfflineMapManager.DownloadState.Error -> {
                        _uiState.update { it.copy(downloadProgress = null, downloadMessage = "Error: ${state.message}") }
                    }
                    is OfflineMapManager.DownloadState.Completed -> {
                        _uiState.update { it.copy(downloadProgress = null, downloadMessage = "Selesai!") }
                    }
                    is OfflineMapManager.DownloadState.Cancelled -> {
                        _uiState.update { it.copy(downloadProgress = null, downloadMessage = "Dibatalkan") }
                    }
                    else -> {}
                }
            }
        }
    }

    fun enableOfflineMode(mapView: MapView) {
        offlineMapManager.enableOfflineMode(mapView)
    }

    // ========== SEGMENT CREATION ==========
    fun startSegmentCreation(): Boolean {
        if (!surveyEngine.isRunning() || surveyEngine.isPaused()) return false
        _uiState.update { it.copy(isCreatingSegment = true) }
        _segmentCreationPoints.value = emptyList()
        return true
    }

    fun setSegmentStartPoint(point: GeoPoint) {
        _segmentCreationPoints.value = listOf(point)
    }

    fun setSegmentEndPoint(point: GeoPoint) {
        val current = _segmentCreationPoints.value.toMutableList()
        if (current.isNotEmpty()) {
            current.add(point)
            _segmentCreationPoints.value = current
        }
    }

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
                if (sensorData?.currentSpeed ?: 0f >= 20f) messages.add("⚠️ Kecepatan > 20 km/h")
                if (state.currentLocation?.accuracy ?: 999f >= 5f) messages.add("⚠️ Akurasi GPS > 5m")
                if (kotlin.math.abs(sensorData?.accelZ ?: 0f) > 1.5f) messages.add("⚠️ Getaran tinggi")
                if (state.currentLocation == null) messages.add("⚠️ GPS tidak tersedia")

                ZAxisValidationResult(isValid = true, messages = messages, confidence = confidence)
            }
            is ZAxisValidation.WarningStopped,
            is ZAxisValidation.InvalidShake,
            is ZAxisValidation.InvalidNoMovement,
            is ZAxisValidation.SuspiciousPattern -> {
                // Buat pesan error berdasarkan tipe validation
                val errorMessage = when (validation) {
                    is ZAxisValidation.WarningStopped -> validation.message ?: "Kendaraan berhenti"
                    is ZAxisValidation.InvalidShake -> validation.message ?: "Getaran tidak valid"
                    is ZAxisValidation.InvalidNoMovement -> validation.message ?: "Tidak ada pergerakan"
                    is ZAxisValidation.SuspiciousPattern -> validation.message ?: "Pola getaran mencurigakan"
                    else -> "Error tidak dikenal"
                }
                ZAxisValidationResult(
                    isValid = false,
                    messages = listOf(errorMessage),
                    confidence = Confidence.LOW
                )
            }
        }
    }

    fun cancelSegmentCreation() {
        _uiState.update { it.copy(isCreatingSegment = false) }
        _segmentCreationPoints.value = emptyList()
    }

    suspend fun saveSegment(
        roadName: String,
        conditionCode: String,
        surfaceCode: String,
        notes: String?
    ) {
        val points = _segmentCreationPoints.value
        if (points.size != 2) return

        val start = points[0]
        val end = points[1]
        val state = _uiState.value
        val sensorData = latestSensorData ?: return

        val qualityScore = qualityScoreCalculator.calculateQualityScore(
            avgIRI = 0.0,
            dataPointCount = state.packetCount,
            gpsAvailabilityPercent = if (state.currentLocation != null) 100.0 else 0.0,
            hasOutliers = false
        ).toFloat() / 100f

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

    private fun buildQualityFlags(sensorData: ESP32SensorData, location: Location?): List<String> {
        val flags = mutableListOf<String>()
        if (location == null) flags.add(QualityFlag.GPS_UNAVAILABLE.name)
        if (sensorData.currentSpeed > 20f) flags.add(QualityFlag.SPEED_TOO_HIGH.name)
        if (kotlin.math.abs(sensorData.accelZ) > 1.5f) flags.add(QualityFlag.VIBRATION_SPIKE.name)
        if (_uiState.value.zAxisConfidence == Confidence.HIGH) flags.add(QualityFlag.HIGH_CONFIDENCE.name)
        sensorData.batteryVoltage?.let { voltage ->
            if (voltage < SurveyConstants.BATTERY_LOW_VOLTAGE) flags.add("BATTERY_WARNING")
        }
        return flags
    }

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
        val zAxisValidation: ZAxisValidation? = null,
        // Untuk download progress
        val downloadProgress: Float? = null,
        val downloadMessage: String? = null
    )

    enum class DeviceReadyState {
        READY, NOT_CONNECTED, CALIBRATION_NEEDED, GPS_DISABLED, BLUETOOTH_DISABLED
    }

    enum class SignalStrength { NONE, SEARCHING, POOR, FAIR, GOOD, EXCELLENT }
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