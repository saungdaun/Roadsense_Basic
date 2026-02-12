package zaujaani.roadsense.features.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
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
import zaujaani.roadsense.domain.model.SurfaceType
import zaujaani.roadsense.domain.usecase.ZAxisValidation
import zaujaani.roadsense.features.survey.SurveyBottomSheet
import java.io.File
import java.util.*

@AndroidEntryPoint
class MapSurveyFragment : Fragment() {

    private var _binding: FragmentMapSurveyBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by viewModels()

    private lateinit var mapView: MapView
    private lateinit var trackingPolyline: Polyline
    private lateinit var segmentPolyline: Polyline
    private lateinit var startMarker: Marker
    private lateinit var endMarker: Marker
    private var currentLocationMarker: Marker? = null
    private var mapTapOverlay: Overlay? = null

    private val locationPermissionRequest = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Timber.d("✅ Location permissions granted")
            if (_binding != null) {
                Snackbar.make(binding.root, getString(R.string.gps_ready), Snackbar.LENGTH_SHORT).show()
            }
        } else {
            if (_binding != null) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.survey_without_gps),
                    Snackbar.LENGTH_LONG
                ).show()
            }
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
        val ctx = requireContext()

        val baseDir = File(ctx.getExternalFilesDir(null), "osmdroid")
        val tileCacheDir = File(baseDir, "tiles/cache")
        val tileArchiveDir = File(baseDir, "tiles")

        tileCacheDir.mkdirs()
        tileArchiveDir.mkdirs()

        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidTileCache = tileCacheDir

            val sharedPrefs = ctx.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
            load(ctx, sharedPrefs)
        }

        mapView = binding.mapView

        val archiveFiles = tileArchiveDir.listFiles { file ->
            file.extension == "sqlite" || file.extension == "zip" || file.extension == "mbtiles"
        }

        if (!archiveFiles.isNullOrEmpty()) {
            try {
                val tileProvider = OfflineTileProvider(SimpleRegisterReceiver(ctx), archiveFiles)
                mapView.setTileProvider(tileProvider)
                Timber.d("✅ Offline tiles loaded (${archiveFiles.size} files)")
            } catch (e: Exception) {
                Timber.e(e, "Offline tile failed — fallback to MAPNIK")
                mapView.setTileSource(TileSourceFactory.MAPNIK)
            }
        } else {
            Timber.d("No offline tiles found — using MAPNIK")
            mapView.setTileSource(TileSourceFactory.MAPNIK)
        }

        mapView.apply {
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(15.0)
        }

        trackingPolyline = Polyline(mapView).apply {
            outlinePaint.color = ContextCompat.getColor(ctx, R.color.tracking_line)
            outlinePaint.strokeWidth = 8f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
        }
        mapView.overlays.add(trackingPolyline)

        segmentPolyline = Polyline(mapView).apply {
            outlinePaint.color = ContextCompat.getColor(ctx, R.color.segment_line)
            outlinePaint.strokeWidth = 12f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
        }
        mapView.overlays.add(segmentPolyline)

        startMarker = Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(ctx, R.drawable.ic_marker_start)
            isDraggable = true
        }

        endMarker = Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(ctx, R.drawable.ic_marker_end)
            isDraggable = true
        }
    }

    private fun setupUI() {
        updateUIState(viewModel.uiState.value)
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (_binding != null) {
                        updateUIState(state)
                        updateMapVisuals(state)
                        updateZAxisValidation(state)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.trackingPoints.collect { points ->
                    if (_binding != null) updateTrackingPolyline(points)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.segmentCreationPoints.collect { points ->
                    if (_binding != null) updateSegmentPolyline(points)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.segments.collect { segments ->
                    if (_binding != null) displaySavedSegments(segments)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.deviceReadyState.collect { state ->
                    if (_binding != null) updateDeviceReadyState(state)
                }
            }
        }
    }

    private fun updateZAxisValidation(state: MapViewModel.MapUiState) {
        if (_binding == null) return

        val validation = state.zAxisValidation
        if (validation == null) {
            binding.tvZAxisStatus.text = getString(R.string.vibration_default)
            binding.btnAddSurvey.isEnabled = false
            return
        }

        val (colorRes, statusText, isAddEnabled) = when (validation) {
            is ZAxisValidation.ValidMoving ->
                Triple(R.color.good_green, validation.message, true)
            is ZAxisValidation.WarningStopped ->
                Triple(R.color.fair_yellow, validation.message, false)
            is ZAxisValidation.InvalidShake ->
                Triple(R.color.heavy_damage_red, validation.message, false)
            is ZAxisValidation.InvalidNoMovement ->
                Triple(R.color.heavy_damage_red, validation.message, false)
            is ZAxisValidation.SuspiciousPattern ->
                Triple(R.color.fair_yellow, validation.message, false)
            else ->
                Triple(R.color.gray_300, getString(R.string.vibration_default), false)
        }

        binding.tvZAxisStatus.apply {
            text = statusText
            val drawable = compoundDrawablesRelative[0]
            drawable?.mutate()?.setTint(ContextCompat.getColor(requireContext(), colorRes))
            setCompoundDrawablesRelative(drawable, null, null, null)
        }

        binding.tvZAxisMessage.apply {
            if (validation is ZAxisValidation.ValidMoving) {
                val confidencePercent = (validation.confidence * 100).toInt()
                text = getString(R.string.confidence_level, "$confidencePercent%")
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        binding.btnAddSurvey.isEnabled = isAddEnabled
    }

    private fun updateDeviceReadyState(state: MapViewModel.DeviceReadyState) {
        if (_binding == null) return
        when (state) {
            MapViewModel.DeviceReadyState.READY -> {
                binding.btnStartStop.isEnabled = true
                binding.tvStatus.text = getString(R.string.status_ready_sensor)
                binding.tvStatus.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.status_ready)
                )
            }
            MapViewModel.DeviceReadyState.NOT_CONNECTED -> {
                binding.btnStartStop.isEnabled = false
                binding.tvStatus.text = getString(R.string.esp32_disconnected)
                if (isAdded) {
                    Snackbar.make(binding.root, getString(R.string.connect_esp32), Snackbar.LENGTH_LONG).show()
                }
            }
            MapViewModel.DeviceReadyState.CALIBRATION_NEEDED -> {
                binding.btnStartStop.isEnabled = false
                binding.tvStatus.text = getString(R.string.calibration_needed)
                if (isAdded) {
                    Snackbar.make(binding.root, getString(R.string.input_wheel_diameter), Snackbar.LENGTH_LONG).show()
                }
            }
            else -> {
                binding.btnStartStop.isEnabled = false
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

        if (mapTapOverlay == null) {
            mapTapOverlay = object : Overlay() {
                override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
                    if (viewModel.uiState.value.isCreatingSegment) {
                        val projection = mapView?.projection
                        val geoPoint = projection?.fromPixels(
                            e?.x?.toInt() ?: 0,
                            e?.y?.toInt() ?: 0
                        )?.let { GeoPoint(it) }
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
        if (_binding == null) return

        binding.tvRecordingStatus.visibility = if (state.isTracking && !state.isPaused) View.VISIBLE else View.GONE
        binding.tvRecordingStatus.text = if (state.isPaused) {
            getString(R.string.status_paused_ui)
        } else {
            getString(R.string.status_recording)
        }
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
        if (_binding == null) return
        val isReady = viewModel.deviceReadyState.value == MapViewModel.DeviceReadyState.READY

        if (state.isTracking) {
            binding.btnStartStop.text = getString(R.string.btn_stop)
            binding.btnStartStop.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_stop)
            binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(
                requireContext(),
                R.color.stop_red
            )
            binding.btnStartStop.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.white)

            binding.btnPauseResume.isEnabled = true
            if (state.isPaused) {
                binding.btnPauseResume.text = getString(R.string.btn_resume)
                binding.btnPauseResume.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_play_arrow)
            } else {
                binding.btnPauseResume.text = getString(R.string.btn_pause)
                binding.btnPauseResume.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_pause)
            }
            binding.btnPauseResume.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.white)

            binding.btnAddSurvey.isEnabled = true
        } else {
            binding.btnStartStop.text = getString(R.string.btn_start)
            binding.btnStartStop.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_play_arrow)
            binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(
                requireContext(),
                R.color.start_green
            )
            binding.btnStartStop.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.white)
            binding.btnStartStop.isEnabled = isReady

            binding.btnPauseResume.isEnabled = false
            binding.btnPauseResume.text = getString(R.string.btn_pause)
            binding.btnPauseResume.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_pause)
            binding.btnPauseResume.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.white)

            binding.btnAddSurvey.isEnabled = false
        }
    }

    private fun updateDataDisplays(state: MapViewModel.MapUiState) {
        if (_binding == null) return
        binding.tvSpeed.text = getString(R.string.speed_format, state.currentSpeed)
        binding.tvDistance.text = getString(R.string.distance_format, state.tripDistance / 1000)
        binding.tvVibration.text = getString(R.string.vibration_format, kotlin.math.abs(state.currentVibration))
        binding.tvConnectionStatus.text = if (state.esp32Connected) {
            getString(R.string.esp32_connected)
        } else {
            getString(R.string.esp32_disconnected)
        }
        binding.tvConnectionStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (state.esp32Connected) R.color.connected_green else R.color.disconnected_red
            )
        )
        binding.tvPacketInfo.text = getString(
            R.string.packet_info_format,
            state.packetCount,
            state.errorCount
        )
    }

    private fun updateGpsStatus(state: MapViewModel.MapUiState) {
        if (_binding == null) return
        when (state.gpsSignalStrength) {
            MapViewModel.SignalStrength.NONE -> {
                binding.tvGpsStatus.text = getString(R.string.gps_unavailable)
                binding.tvGpsStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.gps_none)
                )
            }
            else -> {
                binding.tvGpsStatus.text = getString(R.string.gps_accuracy, state.gpsAccuracy)
                binding.tvGpsStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.gps_good)
                )
            }
        }
    }

    private fun updateMapVisuals(state: MapViewModel.MapUiState) {
        if (_binding == null) return
        state.currentLocation?.let { location ->
            val geoPoint = GeoPoint(location.latitude, location.longitude)

            if (currentLocationMarker == null) {
                currentLocationMarker = Marker(mapView).apply {
                    position = geoPoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_my_location)
                    title = getString(R.string.position_reference, location.accuracy)
                    mapView.overlays.add(this)
                }
            } else {
                currentLocationMarker?.position = geoPoint
                currentLocationMarker?.title = getString(R.string.position_reference, location.accuracy)
            }
        }
    }

    private fun updateTrackingPolyline(points: List<GeoPoint>) {
        if (_binding == null) return
        trackingPolyline.setPoints(points)
        mapView.postInvalidate()
    }

    private fun updateSegmentPolyline(points: List<GeoPoint>) {
        if (_binding == null) return
        segmentPolyline.setPoints(points)

        when (points.size) {
            1 -> {
                startMarker.position = points[0]
                startMarker.title = getString(R.string.segment_start)
                if (!mapView.overlays.contains(startMarker)) {
                    mapView.overlays.add(startMarker)
                }
            }
            2 -> {
                startMarker.position = points[0]
                endMarker.position = points[1]
                startMarker.title = getString(R.string.segment_start)
                endMarker.title = getString(R.string.segment_end)

                if (!mapView.overlays.contains(startMarker)) {
                    mapView.overlays.add(startMarker)
                }
                if (!mapView.overlays.contains(endMarker)) {
                    mapView.overlays.add(endMarker)
                }

                mapView.overlays.removeAll { overlay ->
                    overlay is Marker && overlay.title?.startsWith(getString(R.string.distance_prefix)) == true
                }

                val sensorDistance = viewModel.uiState.value.tripDistance
                val midpoint = GeoPoint(
                    (points[0].latitude + points[1].latitude) / 2,
                    (points[0].longitude + points[1].longitude) / 2
                )
                val distanceMarker = Marker(mapView).apply {
                    position = midpoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = null
                    title = getString(R.string.distance_sensor, sensorDistance)
                }
                mapView.overlays.add(distanceMarker)
            }
        }
        mapView.invalidate()
    }

    private fun displaySavedSegments(segments: List<zaujaani.roadsense.data.local.RoadSegment>) {
        if (_binding == null) return
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

                val surfaceName = when (segment.surface) {
                    SurfaceType.ASPHALT.code -> getString(R.string.asphalt)
                    SurfaceType.CONCRETE.code -> getString(R.string.concrete)
                    SurfaceType.GRAVEL.code -> getString(R.string.gravel)
                    SurfaceType.DIRT.code -> getString(R.string.dirt)
                    else -> getString(R.string.other)
                }

                title = getString(R.string.segment_title, segment.roadName, segment.distanceMeters)
                subDescription = getString(R.string.segment_subdesc, surfaceName, segment.dataSource)
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
                    if (_binding != null) {
                        Snackbar.make(binding.root, getString(R.string.survey_started), Snackbar.LENGTH_SHORT).show()
                    }
                }
                is MapViewModel.SurveyStartResult.ERROR -> {
                    if (_binding != null) {
                        Snackbar.make(binding.root, result.message, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun startSegmentCreation() {
        val success = viewModel.startSegmentCreation()
        if (success) {
            if (_binding != null) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.msg_start_segment),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        } else {
            if (isAdded && _binding != null) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.cannot_create_segment))
                    .setMessage(getString(R.string.survey_required_for_segment))
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
            }
        }
    }

    private fun handleMapTapForSegment(geoPoint: GeoPoint) {
        if (!viewModel.uiState.value.isCreatingSegment) return

        if (viewModel.segmentCreationPoints.value.isEmpty()) {
            viewModel.setSegmentStartPoint(geoPoint)
            if (_binding != null) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.msg_segment_start_set),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        } else {
            viewModel.setSegmentEndPoint(geoPoint)
            val validationResult = viewModel.completeSegmentCreation()

            if (validationResult.isValid) {
                showSurveyBottomSheet(
                    confidence = validationResult.confidence,
                    messages = validationResult.messages
                )
            } else {
                if (isAdded && _binding != null) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.z_axis_validation_failed))
                        .setMessage(validationResult.messages.joinToString("\n"))
                        .setPositiveButton(getString(R.string.ok)) { _, _ ->
                            viewModel.cancelSegmentCreation()
                        }
                        .show()
                }
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
            if (_binding != null) {
                Snackbar.make(binding.root, getString(R.string.map_center_current), Snackbar.LENGTH_SHORT).show()
            }
        } ?: run {
            if (_binding != null) {
                Snackbar.make(binding.root, getString(R.string.gps_unavailable_short), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showStopConfirmationDialog() {
        if (isAdded && _binding != null) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.stop_survey_title))
                .setMessage(getString(R.string.stop_survey_message))
                .setPositiveButton(getString(R.string.stop_and_save)) { _, _ ->
                    lifecycleScope.launch {
                        viewModel.stopSurvey()
                        if (_binding != null) {
                            Snackbar.make(binding.root, getString(R.string.survey_stopped), Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
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
}