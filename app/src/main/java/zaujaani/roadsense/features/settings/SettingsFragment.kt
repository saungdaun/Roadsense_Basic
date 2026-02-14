package zaujaani.roadsense.features.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsense.R
import zaujaani.roadsense.databinding.FragmentSettingsBinding

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSettingsListeners()
        observeViewModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupSettingsListeners() {
        // GPS Settings
        binding.switchGps.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setGpsEnabled(isChecked)
        }
        binding.layoutGps.setOnClickListener {
            viewModel.openGpsSettings()
        }

        // Bluetooth Settings
        binding.switchBluetooth.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setBluetoothEnabled(isChecked)
        }
        binding.layoutBluetooth.setOnClickListener {
            viewModel.openBluetoothSettings()
        }

        // Notification Settings
        binding.switchNotification.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setNotificationsEnabled(isChecked)
        }
        binding.layoutNotification.setOnClickListener {
            viewModel.openAppNotificationSettings()
        }

        // Auto Save Settings
        binding.switchAutoSave.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoSaveEnabled(isChecked)
        }

        // Vibration Detection
        binding.switchVibration.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setVibrationDetectionEnabled(isChecked)
        }

        // Email Settings
        binding.btnSaveEmail.setOnClickListener {
            val email = binding.editTextEmail.text.toString().trim()
            if (email.isNotEmpty()) {
                viewModel.setUserEmail(email)
            } else {
                Snackbar.make(binding.root, "Email tidak boleh kosong", Snackbar.LENGTH_SHORT).show()
            }
        }

        // About Button
        binding.btnAbout.setOnClickListener {
            showAboutDialog()
        }

        // Export Database
        binding.btnExportDatabase.setOnClickListener {
            exportDatabase()
        }

        // Clear Cache
        binding.btnClearCache.setOnClickListener {
            showClearCacheConfirmation()
        }

        // Reset to Defaults
        binding.btnResetDefaults.setOnClickListener {
            showResetConfirmation()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settingsState.collect { state ->
                    // Update switches (tanpa memicu listener berulang)
                    binding.switchGps.apply {
                        setOnCheckedChangeListener(null)
                        isChecked = state.isGpsEnabled
                        setOnCheckedChangeListener { _, isChecked -> viewModel.setGpsEnabled(isChecked) }
                    }
                    binding.switchBluetooth.apply {
                        setOnCheckedChangeListener(null)
                        isChecked = state.isBluetoothEnabled
                        setOnCheckedChangeListener { _, isChecked -> viewModel.setBluetoothEnabled(isChecked) }
                    }
                    binding.switchNotification.apply {
                        setOnCheckedChangeListener(null)
                        isChecked = state.areNotificationsEnabled
                        setOnCheckedChangeListener { _, isChecked -> viewModel.setNotificationsEnabled(isChecked) }
                    }
                    binding.switchAutoSave.apply {
                        setOnCheckedChangeListener(null)
                        isChecked = state.isAutoSaveEnabled
                        setOnCheckedChangeListener { _, isChecked -> viewModel.setAutoSaveEnabled(isChecked) }
                    }
                    binding.switchVibration.apply {
                        setOnCheckedChangeListener(null)
                        isChecked = state.isVibrationDetectionEnabled
                        setOnCheckedChangeListener { _, isChecked -> viewModel.setVibrationDetectionEnabled(isChecked) }
                    }

                    // Update email field
                    binding.editTextEmail.setText(state.userEmail)

                    // Update info lainnya
                    binding.tvAppVersion.text = "Version ${state.appVersion}"
                    binding.tvDatabaseSize.text = state.databaseSize
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isLoading.collect { isLoading ->
                    binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.snackbarMessage.collect { message ->
                    message?.let {
                        Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show()
                        viewModel.clearSnackbar()
                    }
                }
            }
        }
    }

    private fun exportDatabase() {
        viewModel.exportDatabase(
            onSuccess = { uri ->
                shareDatabase(uri)
            }
        )
    }

    private fun shareDatabase(uri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-sqlite3"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Ekspor Database"))
    }

    private fun showClearCacheConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Bersihkan Cache")
            .setMessage("Semua file cache akan dihapus. Lanjutkan?")
            .setPositiveButton("Bersihkan") { _, _ ->
                viewModel.clearCache()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showResetConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Reset Pengaturan")
            .setMessage("Semua pengaturan akan dikembalikan ke default. Lanjutkan?")
            .setPositiveButton("Reset") { _, _ ->
                viewModel.resetToDefaults()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("RoadSense v${viewModel.settingsState.value.appVersion}")
            .setMessage(
                """
                RoadSense - Professional Road Survey Logger
                
                Features:
                • GPS-Resilient Survey
                • Hall Sensor Integration
                • Z-Axis Vibration Analysis
                • Professional Data Quality Metrics
                • Audit-Ready Data Storage
                
                Designed for Indonesian Field Conditions
                
                © 2025 Zaujaani Technologies
                """.trimIndent()
            )
            .setPositiveButton("OK", null)
            .show()
    }
}