package zaujaani.roadsense.features.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
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
        setupSettings()
        setupObservers()
    }



    private fun setupSettings() {
        // GPS Settings
        binding.switchGps.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setGpsEnabled(isChecked)
        }

        // Bluetooth Settings
        binding.switchBluetooth.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setBluetoothEnabled(isChecked)
        }

        // Notification Settings
        binding.switchNotification.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setNotificationsEnabled(isChecked)
        }

        // Auto Save Settings
        binding.switchAutoSave.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoSaveEnabled(isChecked)
        }

        // Vibration Detection
        binding.switchVibration.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setVibrationDetectionEnabled(isChecked)
        }

        // About Button
        binding.btnAbout.setOnClickListener {
            showAboutDialog()
        }

        // Export Database
        binding.btnExportDatabase.setOnClickListener {
            viewModel.exportDatabase()
        }

        // Clear Cache
        binding.btnClearCache.setOnClickListener {
            viewModel.clearCache()
        }
    }

    private fun setupObservers() {
        viewModel.settingsState.observe(viewLifecycleOwner) { state ->
            binding.switchGps.isChecked = state.isGpsEnabled
            binding.switchBluetooth.isChecked = state.isBluetoothEnabled
            binding.switchNotification.isChecked = state.areNotificationsEnabled
            binding.switchAutoSave.isChecked = state.isAutoSaveEnabled
            binding.switchVibration.isChecked = state.isVibrationDetectionEnabled
        }
    }

    private fun showAboutDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("RoadSense v1.0")
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}