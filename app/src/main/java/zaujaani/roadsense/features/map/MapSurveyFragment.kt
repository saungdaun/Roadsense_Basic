package zaujaani.roadsense.features.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.*
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import timber.log.Timber
import zaujaani.roadsense.R
import zaujaani.roadsense.databinding.FragmentMapSurveyBinding

@AndroidEntryPoint
@OptIn(FlowPreview::class)
class MapSurveyFragment : Fragment() {

    private var _binding: FragmentMapSurveyBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by viewModels()

    private lateinit var mapOverlayManager: MapOverlayManager
    private lateinit var uiStateBinder: UIStateBinder
    private lateinit var surveyController: SurveyController

    // Untuk menghindari refresh berulang pada pesan download yang sama
    private var lastDownloadMessage: String? = null

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

        // Setup MenuProvider (menggantikan setHasOptionsMenu + onCreateOptionsMenu)
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.map_survey_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_download_maps -> {
                        val bounds = binding.mapView.boundingBox
                        viewModel.downloadMapArea(requireContext(), bounds, 12..18)
                        Snackbar.make(binding.root, R.string.download_started, Snackbar.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner)

        // Inisialisasi map
        val mapInitResult = MapInitializer.initializeMap(requireContext(), binding.mapView)

        mapOverlayManager = MapOverlayManager(
            context = requireContext(),
            mapView = binding.mapView,
            trackingPolyline = mapInitResult.trackingPolyline,
            segmentPolyline = mapInitResult.segmentPolyline,
            startMarker = mapInitResult.startMarker,
            endMarker = mapInitResult.endMarker
        )

        uiStateBinder = UIStateBinder(binding, requireContext())

        surveyController = SurveyController(
            context = requireContext(),
            viewModel = viewModel,
            lifecycleScope = lifecycleScope,
            snackbarView = binding.root,
            fragmentManager = parentFragmentManager
        )

        mapOverlayManager.onMapTapListener = { geoPoint ->
            surveyController.handleMapTapForSegment(geoPoint)
        }
        mapOverlayManager.setupMapTapOverlay()

        // Aktifkan mode offline pada map (akan memuat tile yang sudah ada)
        viewModel.enableOfflineMode(binding.mapView)

        setupObservers()
        setupClickListeners()
        checkLocationPermissions()

        lifecycleScope.launch {
            viewModel.checkDeviceReadyState()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroyView() {
        mapOverlayManager.onDestroy()
        binding.mapView.onDetach()
        _binding = null
        super.onDestroyView()
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState
                    .debounce(50)
                    .collect { state ->
                        if (_binding != null) {
                            uiStateBinder.bindUiState(state)
                            uiStateBinder.bindControlButtons(
                                isTracking = state.isTracking,
                                isPaused = state.isPaused,
                                isReady = viewModel.deviceReadyState.value == MapViewModel.DeviceReadyState.READY
                            )
                            uiStateBinder.bindZAxisValidation(state.zAxisValidation)

                            state.currentLocation?.let { location ->
                                mapOverlayManager.updateCurrentLocation(location)
                            }

                            // Tampilkan pesan download dan refresh map jika selesai
                            state.downloadMessage?.let { message ->
                                if (message != lastDownloadMessage) {
                                    lastDownloadMessage = message
                                    Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()

                                    // Jika pesan menandakan download sukses, refresh peta agar tile baru tampil
                                    if (message.contains("Selesai", ignoreCase = true) ||
                                        message.contains("sukses", ignoreCase = true)) {
                                        Timber.d("Download completed, refreshing map tiles")
                                        // Aktifkan ulang offline mode untuk memuat tile baru
                                        viewModel.enableOfflineMode(binding.mapView)
                                        // Paksa invalidate untuk refresh tampilan
                                        binding.mapView.invalidate()
                                        // Opsional: ubah zoom sedikit untuk memicu reload
                                        binding.mapView.postDelayed({
                                            val currentZoom = binding.mapView.zoomLevelDouble
                                            binding.mapView.controller.setZoom(currentZoom - 0.01)
                                            binding.mapView.controller.setZoom(currentZoom)
                                        }, 100)
                                    }
                                }
                            }
                        }
                    }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.trackingPoints.collect { points ->
                    if (_binding != null) mapOverlayManager.updateTrackingPolyline(points)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.segmentCreationPoints.collect { points ->
                    if (_binding != null) {
                        mapOverlayManager.updateSegmentPolyline(
                            points = points,
                            sensorDistance = viewModel.uiState.value.tripDistance
                        )
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.segments.collect { segments ->
                    if (_binding != null) mapOverlayManager.displaySavedSegments(segments)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.deviceReadyState.collect { state ->
                    if (_binding != null) uiStateBinder.bindDeviceReadyState(state)
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnStartStop.setOnClickListener {
            if (viewModel.uiState.value.isTracking) {
                surveyController.stopSurveyWithConfirmation()
            } else {
                surveyController.startSurvey()
            }
        }

        binding.btnPauseResume.setOnClickListener {
            surveyController.pauseResumeSurvey()
        }

        binding.btnAddSurvey.setOnClickListener {
            surveyController.startSegmentCreation()
        }

        binding.btnCenterMap.setOnClickListener {
            val location = viewModel.uiState.value.currentLocation
            if (location != null) {
                mapOverlayManager.centerMapOnLocation(
                    GeoPoint(location.latitude, location.longitude),
                    zoom = 16.0
                )
                Snackbar.make(binding.root, getString(R.string.map_center_current), Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, getString(R.string.gps_unavailable_short), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private val locationPermissionRequest = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Timber.d("✅ Location permissions granted")
            Snackbar.make(binding.root, getString(R.string.gps_ready), Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(binding.root, getString(R.string.survey_without_gps), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun checkLocationPermissions() {
        val required = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val missing = required.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            locationPermissionRequest.launch(missing.toTypedArray())
        }
    }
}