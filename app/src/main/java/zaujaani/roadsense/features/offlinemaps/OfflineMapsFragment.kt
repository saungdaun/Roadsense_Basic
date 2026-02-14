package zaujaani.roadsense.features.offlinemaps

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsense.R
import zaujaani.roadsense.databinding.FragmentOfflineMapsBinding
import java.io.File

@AndroidEntryPoint
class OfflineMapsFragment : Fragment() {

    private var _binding: FragmentOfflineMapsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OfflineMapsViewModel by viewModels()

    private lateinit var offlineAdapter: OfflineMapAdapter
    private lateinit var availableAdapter: AvailableMapAdapter

    private var lastLat = -6.9175
    private var lastLon = 107.6191

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            getLastLocation()
        } else {
            Snackbar.make(binding.root, R.string.location_permission_denied, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOfflineMapsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerViews()
        setupListeners()
        observeViewModel()

        viewModel.loadCacheInfo()
        checkLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadCacheInfo()
    }

    private fun setupRecyclerViews() {
        offlineAdapter = OfflineMapAdapter(
            onDeleteClick = { mapFile: OfflineMapFile -> confirmDelete(mapFile) },
            onFileClick = { mapFile: OfflineMapFile -> showFileDetails(mapFile) }
        )
        binding.rvOfflineMaps.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = offlineAdapter
            setHasFixedSize(true)
        }

        availableAdapter = AvailableMapAdapter(
            onDownloadClick = { availableMap: AvailableMap ->
                viewModel.downloadMap(availableMap)
            },
            onItemClick = { availableMap: AvailableMap ->
                showAvailableMapDetails(availableMap)
            }
        )
        binding.rvAvailableMaps.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = availableAdapter
            setHasFixedSize(true)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is OfflineMapsUiState.Loading -> showLoading()
                    is OfflineMapsUiState.Success -> showSuccess(state)
                    is OfflineMapsUiState.Empty -> showEmpty()
                    is OfflineMapsUiState.Error -> showError(state.message)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.availableMaps.collectLatest { list ->
                availableAdapter.submitList(list)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.downloadProgress.collectLatest { progressMap ->
                availableAdapter.setDownloadProgress(progressMap)
                val radiusProgress = progressMap.filterKeys { it.startsWith("radius_") }.values.firstOrNull()
                if (radiusProgress != null) {
                    binding.downloadProgressLayout.visibility = View.VISIBLE
                    binding.downloadProgressBar.progress = radiusProgress.progress
                    binding.downloadProgressText.text = "${radiusProgress.progress}%"
                } else {
                    binding.downloadProgressLayout.visibility = View.GONE
                }
            }
        }
    }

    private fun showLoading() {
        binding.apply {
            progressBar.isVisible = true
            groupContent.isVisible = false
            groupEmpty.isVisible = false
            groupError.isVisible = false
        }
    }

    private fun showSuccess(state: OfflineMapsUiState.Success) {
        binding.apply {
            progressBar.isVisible = false
            groupContent.isVisible = true
            groupEmpty.isVisible = false
            groupError.isVisible = false

            tvStoragePath.text = state.storagePath
            tvTotalSize.text = getString(R.string.total_cache_size, formatFileSize(state.totalSizeMB * 1024 * 1024), formatFileSize(state.maxCacheSizeMB * 1024 * 1024))
            tvTileCount.text = getString(R.string.estimated_tiles, state.estimatedTiles)
            tvAvailableSpace.text = getString(R.string.available_space, formatFileSize(state.availableSpaceMB * 1024 * 1024))

            val usagePercent = (state.totalSizeMB.toFloat() / state.maxCacheSizeMB * 100).toInt().coerceIn(0, 100)
            progressStorage.progress = usagePercent
            progressStorage.setIndicatorColor(
                when {
                    usagePercent < 50 -> ContextCompat.getColor(requireContext(), R.color.good_green)
                    usagePercent < 80 -> ContextCompat.getColor(requireContext(), R.color.fair_yellow)
                    else -> ContextCompat.getColor(requireContext(), R.color.heavy_damage_red)
                }
            )

            offlineAdapter.submitList(state.files)
            tvFileCount.text = getString(R.string.file_count, state.files.size)
        }
    }

    private fun showEmpty() {
        binding.apply {
            progressBar.isVisible = false
            groupContent.isVisible = false
            groupEmpty.isVisible = true
            groupError.isVisible = false

            tvEmptyMessage.text = getString(R.string.no_offline_maps)
            tvEmptyHint.text = getString(R.string.download_maps_hint)
        }
    }

    private fun showError(message: String) {
        binding.apply {
            progressBar.isVisible = false
            groupContent.isVisible = false
            groupEmpty.isVisible = false
            groupError.isVisible = true

            tvErrorMessage.text = message
        }
    }

    private fun setupListeners() {
        binding.btnRefresh.setOnClickListener {
            viewModel.loadCacheInfo()
        }

        binding.btnClearAll.setOnClickListener {
            confirmClearAll()
        }

        binding.btnOpenStorage.setOnClickListener {
            openStorageLocation()
        }

        binding.btnRetry.setOnClickListener {
            viewModel.loadCacheInfo()
        }

        binding.btnDownloadRadius.setOnClickListener {
            showRadiusDialog()
        }
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            getLastLocation()
        } else {
            locationPermissionRequest.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun getLastLocation() {
        // Implementasi dengan FusedLocationProviderClient jika tersedia, untuk sementara default Bandung
        lastLat = -6.9175
        lastLon = 107.6191
    }

    private fun showRadiusDialog() {
        val radii = listOf(5, 10, 20, 50)
        val items = radii.map { "$it km" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_radius)
            .setItems(items) { _, which ->
                val radiusKm = radii[which].toDouble()
                viewModel.downloadRadiusMap(lastLat, lastLon, radiusKm)
            }
            .show()
    }

    private fun confirmDelete(mapFile: OfflineMapFile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_file)
            .setMessage(getString(R.string.confirm_delete_file, mapFile.name))
            .setIcon(R.drawable.ic_delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.deleteFile(mapFile.file).onSuccess {
                        Snackbar.make(binding.root, R.string.file_deleted_successfully, Snackbar.LENGTH_SHORT).show()
                    }.onFailure { error ->
                        Snackbar.make(binding.root, getString(R.string.failed_to_delete_file, error.message ?: "Unknown error"), Snackbar.LENGTH_LONG)
                            .setAction(R.string.retry) { confirmDelete(mapFile) }.show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_all_maps)
            .setMessage(R.string.confirm_delete_all_maps)
            .setIcon(R.drawable.ic_warning)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.clearAllCache().onSuccess {
                        Snackbar.make(binding.root, R.string.all_maps_cleared, Snackbar.LENGTH_SHORT).show()
                    }.onFailure { error ->
                        Snackbar.make(binding.root, getString(R.string.failed_to_clear_cache, error.message ?: "Unknown error"), Snackbar.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFileDetails(mapFile: OfflineMapFile) {
        val details = buildString {
            appendLine("📁 ${getString(R.string.file_name)}: ${mapFile.name}")
            appendLine("📊 ${getString(R.string.size)}: ${mapFile.formattedSize}")
            appendLine("📍 ${getString(R.string.path)}: ${mapFile.path}")
            appendLine("🕐 ${getString(R.string.modified)}: ${mapFile.formattedDate}")
            appendLine("📝 ${getString(R.string.type)}: ${mapFile.type}")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.file_details)
            .setMessage(details)
            .setIcon(R.drawable.ic_info)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.open_location) { _, _ ->
                copyPathToClipboard(mapFile.path)
                Snackbar.make(binding.root, R.string.path_copied, Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showAvailableMapDetails(map: AvailableMap) {
        val details = buildString {
            appendLine("🗺️ ${getString(R.string.name)}: ${map.name}")
            appendLine("📝 ${getString(R.string.description)}: ${map.description}")
            appendLine("📦 ${getString(R.string.size)}: ${formatFileSize(map.sizeMb * 1024 * 1024)}")
            appendLine("🏷️ ${getString(R.string.provider)}: ${map.provider}")
            appendLine("🔗 URL: ${map.url}")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.map_details)
            .setMessage(details)
            .setIcon(R.drawable.ic_info)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun openStorageLocation() {
        val state = viewModel.uiState.value
        if (state is OfflineMapsUiState.Success) {
            val path = state.storagePath
            showPathDialog(
                title = getString(R.string.storage_location),
                path = path,
                onCopy = { copyPathToClipboard(path) }
            )
        } else {
            openStorageSettings()
        }
    }

    private fun openFileLocation(file: File) {
        val path = file.absolutePath
        showPathDialog(
            title = getString(R.string.file_location),
            path = path,
            onCopy = { copyPathToClipboard(path) }
        )
    }

    private fun showPathDialog(title: String, path: String, onCopy: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(path)
            .setPositiveButton(R.string.copy) { _, _ ->
                onCopy()
                Snackbar.make(binding.root, R.string.path_copied, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun copyPathToClipboard(path: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Path", path)
        clipboard.setPrimaryClip(clip)
    }

    private fun openStorageSettings() {
        try {
            val intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open storage settings")
            Snackbar.make(binding.root, R.string.cannot_open_storage, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}