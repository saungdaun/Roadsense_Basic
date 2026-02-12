package zaujaani.roadsense.features.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import timber.log.Timber
import zaujaani.roadsense.R
import zaujaani.roadsense.databinding.FragmentMapSurveyBinding
import zaujaani.roadsense.domain.model.Confidence
import zaujaani.roadsense.domain.model.RoadCondition
import zaujaani.roadsense.features.survey.SurveyBottomSheet
import java.util.*
import kotlin.math.abs

@AndroidEntryPoint
class MapSurveyFragment : Fragment() {

    private var _binding: FragmentMapSurveyBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by viewModels()

    // OSMDroid components
    private lateinit var mapView: MapView
    private lateinit var trackingPolyline: Polyline
    private lateinit var segmentPolyline: Polyline
    private lateinit var startMarker: Marker
    private lateinit var endMarker: Marker
    private var currentLocationMarker: Marker? = null
    private var mapTapOverlay: Overlay? = null

    // Permission request
    private val locationPermissionRequest = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Timber.d("✅ Location permissions granted")
            Snackbar.make(binding.root, "GPS ready for position reference", Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(
                binding.root,
                "⚠️ Survey tetap bisa berjalan tanpa GPS (menggunakan sensor)",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapSurveyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeMap()
        setupUI()
        setupObservers()
        setupClickListeners()
        checkLocationPermissions()

        lifecycleScope.launch {
            viewModel.checkDeviceReadyState()
        }
    }

    private fun initializeMap() {
        mapView = binding.mapView

        Configuration.getInstance().apply {
            userAgentValue = requireContext().packageName
            val sharedPrefs = requireContext().getSharedPreferences("osmdroid_prefs", android.content.Context.MODE_PRIVATE)
            load(requireContext(), sharedPrefs)
        }

        mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            // Kontrol zoom modern – gak pakai setBuiltInZoomControls (deprecated)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(15.0)
        }

        // Polyline tracking (putih solid)
        trackingPolyline = Polyline(mapView).apply {
            outlinePaint.color = ContextCompat.getColor(requireContext(), R.color.tracking_line)
            outlinePaint.strokeWidth = 8f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
        mapView.overlays.add(trackingPolyline)

        // Polyline segment (biru putus-putus)
        segmentPolyline = Polyline(mapView).apply {
            outlinePaint.color = ContextCompat.getColor(requireContext(), R.color.segment_line)
            outlinePaint.strokeWidth = 12f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
        }
        mapView.overlays.add(segmentPolyline)

        // Marker start & end
        startMarker = Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_start)
            isDraggable = true
        }
        endMarker = Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_end)
            isDraggable = true
        }
    }

    private fun setupUI() {
        updateUIState(viewModel.uiState.value)
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUIState(state)
                updateMapVisuals(state)
                updateZAxisValidation(state)
            }
        }

        lifecycleScope.launch {
            viewModel.trackingPoints.collect { points ->
                updateTrackingPolyline(points)
            }
        }

        lifecycleScope.launch {
            viewModel.segmentCreationPoints.collect { points ->
                updateSegmentPolyline(points)
            }
        }

        lifecycleScope.launch {
            viewModel.segments.collect { segments ->
                displaySavedSegments(segments)
            }
        }

        lifecycleScope.launch {
            viewModel.deviceReadyState.collect { state ->
                updateDeviceReadyState(state)
            }
        }
    }

    private fun updateDeviceReadyState(state: MapViewModel.DeviceReadyState) {
        when (state) {
            MapViewModel.DeviceReadyState.READY -> {
                binding.btnStartStop.isEnabled = true
                binding.tvStatus.text = "READY - Sensor sebagai sumber jarak"
                binding.tvStatus.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.status_ready)
                )
            }
            MapViewModel.DeviceReadyState.NOT_CONNECTED -> {
                binding.btnStartStop.isEnabled = false
                binding.tvStatus.text = "ESP32 not connected"
                Snackbar.make(binding.root, "Connect ESP32 to start survey", Snackbar.LENGTH_LONG).show()
            }
            MapViewModel.DeviceReadyState.CALIBRATION_NEEDED -> {
                binding.btnStartStop.isEnabled = false
                binding.tvStatus.text = "Calibration needed"
                Snackbar.make(binding.root, "Input wheel diameter before survey", Snackbar.LENGTH_LONG).show()
            }
            else -> {
                binding.btnStartStop.isEnabled = false
            }
        }
    }

    private fun updateZAxisValidation(state: MapViewModel.MapUiState) {
        val zAxisState = when {
            state.currentSpeed > 5f && abs(state.currentVibration) < 2.0f -> ZAxisState.VALID_MOVING
            state.currentSpeed < 1f -> ZAxisState.WARNING_STOPPED
            abs(state.currentVibration) > 5.0f -> ZAxisState.INVALID_SHAKE
            else -> ZAxisState.VALID_MOVING
        }

        when (zAxisState) {
            ZAxisState.VALID_MOVING -> {
                binding.zAxisIndicator.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.good_green)
                )
                binding.tvZAxisStatus.text = "✅ Valid - Siap merekam"
                binding.btnAddSurvey.isEnabled = true
            }
            ZAxisState.WARNING_STOPPED -> {
                binding.zAxisIndicator.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.fair_yellow)
                )
                binding.tvZAxisStatus.text = "⚠️ Kendaraan berhenti"
                binding.btnAddSurvey.isEnabled = false
            }
            ZAxisState.INVALID_SHAKE -> {
                binding.zAxisIndicator.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.heavy_damage_red)
                )
                binding.tvZAxisStatus.text = "❌ Getaran ekstrem"
                binding.btnAddSurvey.isEnabled = false
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnStartStop.setOnClickListener {
            if (viewModel.uiState.value.isTracking) {
                showStopConfirmationDialog()
            } else {
                startSurvey()
            }
        }

        binding.btnPauseResume.setOnClickListener {
            if (viewModel.uiState.value.isPaused) {
                viewModel.resumeSurvey()
            } else {
                viewModel.pauseSurvey()
            }
        }

        binding.btnAddSurvey.setOnClickListener {
            startSegmentCreation()
        }

        binding.btnCenterMap.setOnClickListener {
            centerMapOnCurrentLocation()
        }

        // Overlay untuk tap pada saat pembuatan segment
        if (mapTapOverlay == null) {
            mapTapOverlay = object : Overlay() {
                override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
                    if (viewModel.uiState.value.isCreatingSegment) {
                        val projection = mapView?.projection
                        val geoPoint = projection?.fromPixels(e?.x?.toInt() ?: 0, e?.y?.toInt() ?: 0)
                            ?.let { GeoPoint(it) }
                        geoPoint?.let { point ->
                            handleMapTapForSegment(point)
                        }
                        return true
                    }
                    return false
                }
            }
            mapTapOverlay?.let { mapView.overlays.add(it) }
        }
    }

    private fun updateUIState(state: MapViewModel.MapUiState) {
        binding.tvRecordingStatus.visibility = if (state.isTracking && !state.isPaused) View.VISIBLE else View.GONE
        binding.tvRecordingStatus.text = if (state.isPaused) "PAUSED" else "RECORDING"
        binding.tvRecordingStatus.setBackgroundColor(
            ContextCompat.getColor(
                requireContext(),
                if (state.isPaused) R.color.pause_yellow else R.color.stop_red
            )
        )

        updateControlButtons(state)
        updateDataDisplays(state)
        updateGpsStatus(state)
    }

    private fun updateControlButtons(state: MapViewModel.MapUiState) {
        val isReady = viewModel.deviceReadyState.value == MapViewModel.DeviceReadyState.READY

        if (state.isTracking) {
            // Tombol START → STOP
            binding.btnStartStop.text = getString(R.string.btn_stop)
            binding.btnStartStop.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_stop)
            binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(
                requireContext(),
                R.color.stop_red
            )
            binding.btnStartStop.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.white)

            // Tombol PAUSE / RESUME
            binding.btnPauseResume.isEnabled = true
            if (state.isPaused) {
                binding.btnPauseResume.text = getString(R.string.btn_resume)
                binding.btnPauseResume.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_play_arrow)
            } else {
                binding.btnPauseResume.text = getString(R.string.btn_pause)
                binding.btnPauseResume.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_pause)
            }
            binding.btnPauseResume.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.white)

            // Tombol Add Survey aktif
            binding.btnAddSurvey.isEnabled = true
        } else {
            // Tombol STOP → START
            binding.btnStartStop.text = getString(R.string.btn_start)
            binding.btnStartStop.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_play_arrow)
            binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(
                requireContext(),
                R.color.start_green
            )
            binding.btnStartStop.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.white)
            binding.btnStartStop.isEnabled = isReady

            // Tombol PAUSE tidak aktif
            binding.btnPauseResume.isEnabled = false
            binding.btnPauseResume.text = getString(R.string.btn_pause)
            binding.btnPauseResume.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_pause)
            binding.btnPauseResume.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.white)

            // Tombol Add Survey nonaktif
            binding.btnAddSurvey.isEnabled = false
        }
    }

    private fun updateDataDisplays(state: MapViewModel.MapUiState) {
        binding.tvSpeed.text = String.format(
            Locale.getDefault(),
            "%.1f km/h",
            state.currentSpeed
        )
        binding.tvDistance.text = String.format(
            Locale.getDefault(),
            "%.2f km",
            state.tripDistance / 1000
        )
        binding.tvVibration.text = String.format(
            Locale.getDefault(),
            "Z: %.2fg",
            abs(state.currentVibration)
        )
        binding.tvConnectionStatus.text = if (state.esp32Connected)
            "ESP32: ✅ Connected" else "ESP32: ❌ Disconnected"
        binding.tvConnectionStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (state.esp32Connected) R.color.connected_green else R.color.disconnected_red
            )
        )
        binding.tvPacketInfo.text = "Packets: ${state.packetCount} | Errors: ${state.errorCount}"
    }

    private fun updateGpsStatus(state: MapViewModel.MapUiState) {
        when (state.gpsSignalStrength) {
            MapViewModel.SignalStrength.NONE -> {
                binding.tvGpsStatus.text = "📍 GPS: Unavailable (Using Sensor)"
                binding.tvGpsStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.gps_none)
                )
            }
            else -> {
                binding.tvGpsStatus.text = "📍 GPS: ${state.gpsAccuracy}"
                binding.tvGpsStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.gps_good)
                )
            }
        }
    }

    private fun updateMapVisuals(state: MapViewModel.MapUiState) {
        state.currentLocation?.let { location ->
            val geoPoint = GeoPoint(location.latitude, location.longitude)

            if (currentLocationMarker == null) {
                currentLocationMarker = Marker(mapView).apply {
                    position = geoPoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_my_location)
                    title = "Position Reference\nAccuracy: ${location.accuracy}m"
                    mapView.overlays.add(this)
                }
            } else {
                currentLocationMarker?.position = geoPoint
                currentLocationMarker?.title = "Position Reference\nAccuracy: ${location.accuracy}m"
            }
        }
    }

    private fun updateTrackingPolyline(points: List<GeoPoint>) {
        trackingPolyline.setPoints(points)
        mapView.invalidate()
    }

    private fun updateSegmentPolyline(points: List<GeoPoint>) {
        segmentPolyline.setPoints(points)

        when (points.size) {
            1 -> {
                startMarker.position = points[0]
                startMarker.title = "Segment Start"
                if (!mapView.overlays.contains(startMarker)) {
                    mapView.overlays.add(startMarker)
                }
            }
            2 -> {
                startMarker.position = points[0]
                endMarker.position = points[1]
                startMarker.title = "Segment Start"
                endMarker.title = "Segment End"

                if (!mapView.overlays.contains(startMarker)) {
                    mapView.overlays.add(startMarker)
                }
                if (!mapView.overlays.contains(endMarker)) {
                    mapView.overlays.add(endMarker)
                }

                // Hapus marker jarak sebelumnya
                mapView.overlays.removeAll { overlay ->
                    overlay is Marker && overlay.title?.startsWith("Distance") == true
                }

                // Marker jarak tanpa icon (hanya teks)
                val sensorDistance = viewModel.uiState.value.tripDistance
                val midpoint = GeoPoint(
                    (points[0].latitude + points[1].latitude) / 2,
                    (points[0].longitude + points[1].longitude) / 2
                )
                val distanceMarker = Marker(mapView).apply {
                    position = midpoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = null
                    title = "Distance (Sensor): ${String.format("%.1f", sensorDistance)}m"
                }
                mapView.overlays.add(distanceMarker)
            }
        }
        mapView.invalidate()
    }

    private fun displaySavedSegments(segments: List<zaujaani.roadsense.data.local.RoadSegment>) {
        val toRemove = mapView.overlays.filter { overlay ->
            overlay is Polyline && overlay != trackingPolyline && overlay != segmentPolyline
        }.toList()
        toRemove.forEach { mapView.overlays.remove(it) }

        segments.forEach { segment ->
            val segmentPolyline = Polyline(mapView).apply {
                setPoints(
                    listOf(
                        GeoPoint(segment.startLatitude, segment.startLongitude),
                        GeoPoint(segment.endLatitude, segment.endLongitude)
                    )
                )
                val color = when (segment.condition) {
                    RoadCondition.GOOD.code -> ContextCompat.getColor(requireContext(), R.color.condition_good)
                    RoadCondition.MODERATE.code -> ContextCompat.getColor(requireContext(), R.color.condition_moderate)
                    RoadCondition.LIGHT_DAMAGE.code -> ContextCompat.getColor(requireContext(), R.color.condition_light_damage)
                    else -> ContextCompat.getColor(requireContext(), R.color.condition_heavy_damage)
                }
                outlinePaint.color = color
                outlinePaint.strokeWidth = 8f
                title = "${segment.roadName} - ${segment.distanceMeters}m"
                subDescription = "Source: ${segment.dataSource}"
            }
            mapView.overlays.add(segmentPolyline)
        }
        mapView.invalidate()
    }

    private fun checkLocationPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            locationPermissionRequest.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startSurvey() {
        lifecycleScope.launch {
            val result = viewModel.startSurvey()
            when (result) {
                is MapViewModel.SurveyStartResult.SUCCESS -> {
                    Snackbar.make(binding.root, "Survey started - Sensor as primary source", Snackbar.LENGTH_SHORT).show()
                }
                is MapViewModel.SurveyStartResult.ERROR -> {
                    Snackbar.make(binding.root, result.message, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startSegmentCreation() {
        val success = viewModel.startSegmentCreation()
        if (success) {
            Snackbar.make(
                binding.root,
                "Tap on map to set segment points (GPS optional)",
                Snackbar.LENGTH_LONG
            ).show()
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Cannot Create Segment")
                .setMessage("Survey must be active to create segments")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun handleMapTapForSegment(geoPoint: GeoPoint) {
        if (!viewModel.uiState.value.isCreatingSegment) return

        if (viewModel.segmentCreationPoints.value.isEmpty()) {
            viewModel.setSegmentStartPoint(geoPoint)
            Snackbar.make(
                binding.root,
                "Start point set (GPS position reference)",
                Snackbar.LENGTH_SHORT
            ).show()
        } else {
            viewModel.setSegmentEndPoint(geoPoint)
            val validationResult = viewModel.completeSegmentCreation()

            if (validationResult.isValid) {
                showSurveyBottomSheet(
                    confidence = validationResult.confidence,
                    messages = validationResult.messages
                )
            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Z-Axis Validation Failed")
                    .setMessage(validationResult.messages.joinToString("\n"))
                    .setPositiveButton("OK") { _, _ ->
                        viewModel.cancelSegmentCreation()
                    }
                    .show()
            }
        }
    }

    private fun showSurveyBottomSheet(confidence: Confidence, messages: List<String>) {
        val bottomSheet = SurveyBottomSheet.newInstance(confidence, messages)
        bottomSheet.setOnSaveListener { roadName, condition, surface, notes ->
            lifecycleScope.launch {
                viewModel.saveSegment(roadName, condition, surface, notes)
            }
        }
        bottomSheet.show(parentFragmentManager, "SurveyBottomSheet")
    }

    private fun centerMapOnCurrentLocation() {
        val location = viewModel.uiState.value.currentLocation
        location?.let {
            val geoPoint = GeoPoint(it.latitude, it.longitude)
            mapView.controller.animateTo(geoPoint, 16.0, 1000L)
            Snackbar.make(binding.root, R.string.map_center_current, Snackbar.LENGTH_SHORT).show()
        } ?: run {
            Snackbar.make(binding.root, "No GPS reference available", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showStopConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Stop Survey")
            .setMessage("Stop survey and save data?")
            .setPositiveButton("Stop & Save") { _, _ ->
                lifecycleScope.launch {
                    viewModel.stopSurvey()
                    Snackbar.make(binding.root, "Survey stopped - Data saved", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView.onDetach()
        _binding = null
    }

    enum class ZAxisState {
        VALID_MOVING,
        WARNING_STOPPED,
        INVALID_SHAKE
    }
}