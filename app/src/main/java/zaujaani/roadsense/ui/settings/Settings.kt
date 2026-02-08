package zaujaani.roadsense.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import zaujaani.roadsense.R
import zaujaani.roadsense.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

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

        setupToolbar()
        setupClickListeners()
    }

    private fun setupToolbar() {
        // Setup back navigation di toolbar
        binding.toolbar.setNavigationOnClickListener {
            // Kembali ke fragment sebelumnya
            findNavController().navigateUp()
        }
    }

    private fun setupClickListeners() {
        // Bluetooth Settings
        binding.bluetoothSettings.setOnClickListener {
            // TODO: Implement bluetooth settings
        }

        // GPS Settings
        binding.gpsSettings.setOnClickListener {
            // TODO: Implement GPS settings
        }

        // Data Settings
        binding.dataSettings.setOnClickListener {
            // TODO: Implement data settings
        }

        // Help & Support - Navigasi ke HelpFragment
        binding.helpSupport.setOnClickListener {
            findNavController().navigate(R.id.helpFragment)
        }

        // About - Navigasi ke AboutFragment atau tampilkan dialog
        binding.about.setOnClickListener {
            // TODO: Implement about
            // findNavController().navigate(R.id.aboutFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}