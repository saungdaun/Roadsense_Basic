package zaujaani.roadsense.features.map

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import timber.log.Timber
import zaujaani.roadsense.R
import zaujaani.roadsense.databinding.FragmentMapSurveyBinding
import java.io.InputStream

@AndroidEntryPoint
@OptIn(FlowPreview::class)
class MapSurveyFragment : Fragment(), MenuProvider {

    private var _binding: FragmentMapSurveyBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by viewModels()

    private lateinit var mapOverlayManager: MapOverlayManager
    private lateinit var uiStateBinder: UIStateBinder
    private lateinit var surveyController: SurveyController

    private var lastDownloadMessage: String? = null

    // File picker untuk import GPS
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { processImportedFile(it) }
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

        requireActivity().addMenuProvider(this, viewLifecycleOwner)

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

        viewModel.enableOfflineMode(binding.mapView)

        setupObservers()
        setupClickListeners()
        checkLocationPermissions()

        lifecycleScope.launch {
            viewModel.checkDeviceReadyState()
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.map_survey_menu, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.action_download_maps -> {
                val bounds = binding.mapView.boundingBox
                viewModel.downloadMapArea(bounds, 12..18)
                Snackbar.make(binding.root, R.string.download_started, Snackbar.LENGTH_SHORT).show()
                true
            }
            R.id.action_import_gps -> {
                openFilePicker()
                true
            }
            R.id.action_clear_imported -> {
                mapOverlayManager.clearImportedTracks()
                Snackbar.make(binding.root, "Semua track impor dihapus", Snackbar.LENGTH_SHORT).show()
                true
            }
            else -> false
        }
    }

    private fun openFilePicker() {
        // MIME types yang didukung
        val mimeTypes = arrayOf(
            "application/gpx+xml",
            "application/vnd.google-earth.kml+xml",
            "application/json",
            "*/*" // fallback
        )
        filePickerLauncher.launch("*/*")
    }

    private fun processImportedFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val fileName = uri.lastPathSegment ?: "import"
                val mimeType = requireContext().contentResolver.getType(uri)

                val points = when {
                    fileName.endsWith(".gpx", ignoreCase = true) || mimeType == "application/gpx+xml" ->
                        parseGpx(inputStream)
                    fileName.endsWith(".kml", ignoreCase = true) || mimeType == "application/vnd.google-earth.kml+xml" ->
                        parseKml(inputStream)
                    fileName.endsWith(".json", ignoreCase = true) || mimeType == "application/json" ->
                        parseGeoJson(inputStream)
                    else -> throw Exception(getString(R.string.file_not_supported))
                }

                if (points.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Snackbar.make(binding.root, "File tidak mengandung data track", Snackbar.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    mapOverlayManager.addImportedTrack(points)
                    val message = getString(R.string.import_success, points.size)
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Timber.e(e, "Gagal mengimpor file")
                withContext(Dispatchers.Main) {
                    val errorMsg = getString(R.string.import_failed, e.message)
                    Snackbar.make(binding.root, errorMsg, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    // ==================== PARSING FUNCTIONS ====================

    private fun parseGpx(inputStream: InputStream?): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        if (inputStream == null) return points

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(inputStream, "UTF-8")

            var eventType = parser.eventType
            var inTrkpt = false
            var lat = 0.0
            var lon = 0.0

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "trkpt" -> {
                                inTrkpt = true
                                lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                                lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "trkpt" && inTrkpt) {
                            points.add(GeoPoint(lat, lon))
                            inTrkpt = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Timber.e(e, "GPX parsing error")
            throw e
        } finally {
            inputStream.close()
        }
        return points
    }

    private fun parseKml(inputStream: InputStream?): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        if (inputStream == null) return points

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(inputStream, "UTF-8")

            var eventType = parser.eventType
            var inCoordinates = false
            var coordText = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "LineString" || parser.name == "coordinates") {
                            inCoordinates = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inCoordinates) {
                            coordText = parser.text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "coordinates" && inCoordinates) {
                            // Parse "lon,lat,alt lon,lat,alt ..."
                            val parts = coordText.trim().split(Regex("\\s+"))
                            for (part in parts) {
                                val coords = part.split(",")
                                if (coords.size >= 2) {
                                    val lon = coords[0].toDoubleOrNull()
                                    val lat = coords[1].toDoubleOrNull()
                                    if (lat != null && lon != null) {
                                        points.add(GeoPoint(lat, lon))
                                    }
                                }
                            }
                            inCoordinates = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Timber.e(e, "KML parsing error")
            throw e
        } finally {
            inputStream.close()
        }
        return points
    }

    private fun parseGeoJson(inputStream: InputStream?): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        if (inputStream == null) return points

        try {
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)

            // Cek apakah ada features array (GeoJSON FeatureCollection)
            if (json.has("features")) {
                val features = json.getJSONArray("features")
                for (i in 0 until features.length()) {
                    val feature = features.getJSONObject(i)
                    val geometry = feature.optJSONObject("geometry")
                    if (geometry != null) {
                        extractCoordinates(geometry, points)
                    }
                }
            } else if (json.has("geometry")) {
                // Single Feature
                val geometry = json.getJSONObject("geometry")
                extractCoordinates(geometry, points)
            } else {
                // Mungkin langsung geometry (LineString)
                extractCoordinates(json, points)
            }
        } catch (e: Exception) {
            Timber.e(e, "GeoJSON parsing error")
            throw e
        } finally {
            inputStream.close()
        }
        return points
    }

    private fun extractCoordinates(geometry: JSONObject, points: MutableList<GeoPoint>) {
        val type = geometry.getString("type")
        if (type == "LineString") {
            val coordinates = geometry.getJSONArray("coordinates")
            for (j in 0 until coordinates.length()) {
                val coord = coordinates.getJSONArray(j)
                val lon = coord.getDouble(0)
                val lat = coord.getDouble(1)
                points.add(GeoPoint(lat, lon))
            }
        } else if (type == "MultiLineString") {
            val lines = geometry.getJSONArray("coordinates")
            for (i in 0 until lines.length()) {
                val line = lines.getJSONArray(i)
                for (j in 0 until line.length()) {
                    val coord = line.getJSONArray(j)
                    val lon = coord.getDouble(0)
                    val lat = coord.getDouble(1)
                    points.add(GeoPoint(lat, lon))
                }
            }
        }
    }

    // ==============================================================

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

                            state.downloadMessage?.let { message ->
                                if (message != lastDownloadMessage) {
                                    lastDownloadMessage = message
                                    Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()

                                    if (message.contains("Selesai", ignoreCase = true) ||
                                        message.contains("sukses", ignoreCase = true)) {
                                        Timber.d("Download completed, refreshing map tiles")
                                        viewModel.enableOfflineMode(binding.mapView)
                                        binding.mapView.invalidate()
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