package zaujaani.roadsense.features.offlinemaps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class OfflineMapsFragment : Fragment() {

    private var _binding: FragmentOfflineMapsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OfflineMapsViewModel by viewModels()

    private lateinit var adapter: OfflineMapAdapter

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

        setupRecyclerView()
        setupListeners()
        observeViewModel()

        viewModel.loadCacheInfo()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadCacheInfo()
    }

    private fun setupRecyclerView() {
        adapter = OfflineMapAdapter(
            onDeleteClick = { mapFile -> confirmDelete(mapFile) },
            onFileClick = { mapFile -> showFileDetails(mapFile) }
        )

        binding.rvOfflineMaps.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@OfflineMapsFragment.adapter
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

            // Lokasi penyimpanan
            tvStoragePath.text = state.storagePath

            // Total ukuran
            tvTotalSize.text = getString(
                R.string.total_cache_size,
                formatFileSize(state.totalSizeMB * 1024 * 1024), // konversi MB ke bytes untuk formatting
                formatFileSize(state.maxCacheSizeMB * 1024 * 1024)
            )

            // Perkiraan tile
            tvTileCount.text = getString(R.string.estimated_tiles, state.estimatedTiles)

            // Ruang tersedia
            tvAvailableSpace.text = getString(R.string.available_space, formatFileSize(state.availableSpaceMB * 1024 * 1024))

            // Progress bar penggunaan
            val usagePercent = (state.totalSizeMB.toFloat() / state.maxCacheSizeMB * 100).toInt().coerceIn(0, 100)
            progressStorage.progress = usagePercent

            // Warna indikator berdasarkan penggunaan
            progressStorage.setIndicatorColor(
                when {
                    usagePercent < 50 -> ContextCompat.getColor(requireContext(), R.color.good_green)
                    usagePercent < 80 -> ContextCompat.getColor(requireContext(), R.color.fair_yellow)
                    else -> ContextCompat.getColor(requireContext(), R.color.heavy_damage_red)
                }
            )

            // Submit daftar file ke adapter
            adapter.submitList(state.files)

            // Jumlah file
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
    }

    private fun confirmDelete(mapFile: OfflineMapFile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_file)
            .setMessage(getString(R.string.confirm_delete_file, mapFile.name))
            .setIcon(R.drawable.ic_delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteFile(mapFile.file)
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
                clearAllMaps()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteFile(file: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.deleteFile(file).onSuccess {
                Snackbar.make(
                    binding.root,
                    R.string.file_deleted_successfully,
                    Snackbar.LENGTH_SHORT
                ).show()
            }.onFailure { error ->
                Snackbar.make(
                    binding.root,
                    getString(R.string.failed_to_delete_file, error.message ?: "Unknown error"),
                    Snackbar.LENGTH_LONG
                ).setAction(R.string.retry) {
                    deleteFile(file)
                }.show()
            }
        }
    }

    private fun clearAllMaps() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.clearAllCache().onSuccess {
                Snackbar.make(
                    binding.root,
                    R.string.all_maps_cleared,
                    Snackbar.LENGTH_SHORT
                ).show()
            }.onFailure { error ->
                Snackbar.make(
                    binding.root,
                    getString(R.string.failed_to_clear_cache, error.message ?: "Unknown error"),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
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
                openFileLocation(mapFile.file)
            }
            .show()
    }

    private fun openStorageLocation() {
        try {
            val state = viewModel.uiState.value
            if (state is OfflineMapsUiState.Success) {
                val path = state.storagePath

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(path), "resource/folder")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    openStorageSettings()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to open storage location")
            Snackbar.make(
                binding.root,
                R.string.cannot_open_storage,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun openFileLocation(file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(file.parentFile), "resource/folder")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open file location")
            Snackbar.make(
                binding.root,
                R.string.cannot_open_location,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun openStorageSettings() {
        try {
            val intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open storage settings")
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