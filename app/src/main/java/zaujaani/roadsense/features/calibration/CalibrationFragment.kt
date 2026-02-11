package zaujaani.roadsense.features.calibration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import zaujaani.roadsense.databinding.FragmentCalibrationBinding

@AndroidEntryPoint
class CalibrationFragment : Fragment() {

    private var _binding: FragmentCalibrationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CalibrationViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCalibrationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupForm()
        setupObservers()
        loadCalibration()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            // Use OnBackPressedDispatcher instead of deprecated onBackPressed()
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupForm() {
        // Wheel diameter unit selection
        binding.radioGroupUnits.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                binding.radioCm.id -> updateUnit("cm")
                binding.radioInch.id -> updateUnit("inch")
            }
        }

        // Save button
        binding.btnSaveCalibration.setOnClickListener {
            saveCalibration()
        }

        // Test button
        binding.btnTestCalibration.setOnClickListener {
            testCalibration()
        }
    }

    private fun updateUnit(unit: String) {
        when (unit) {
            "cm" -> {
                binding.tilWheelDiameter.hint = "Diameter Roda (cm)"
                binding.tilWheelDiameter.suffixText = "cm"
            }
            "inch" -> {
                binding.tilWheelDiameter.hint = "Diameter Roda (inch)"
                binding.tilWheelDiameter.suffixText = "inch"
            }
        }
    }

    private fun loadCalibration() {
        viewModel.loadCalibration()
    }

    private fun setupObservers() {
        viewModel.calibrationState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is CalibrationViewModel.CalibrationState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                }
                is CalibrationViewModel.CalibrationState.Loaded -> {
                    binding.progressBar.visibility = View.GONE
                    state.calibration?.let { calibration ->
                        binding.etDeviceName.setText(calibration.deviceName)
                        binding.etWheelDiameter.setText(calibration.wheelDiameter.toString())
                        binding.etPulsesPerRotation.setText(calibration.pulsesPerRotation.toString())

                        when (calibration.wheelDiameterUnit) {
                            "cm" -> binding.radioCm.isChecked = true
                            "inch" -> binding.radioInch.isChecked = true
                        }

                        binding.etVehicleType.setText(calibration.vehicleType ?: "")
                        binding.etTirePressure.setText(calibration.tirePressure?.toString() ?: "")
                        binding.etLoadWeight.setText(calibration.loadWeight?.toString() ?: "")
                    }
                }
                is CalibrationViewModel.CalibrationState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
                }
                is CalibrationViewModel.CalibrationState.Saved -> {
                    binding.progressBar.visibility = View.GONE
                    Snackbar.make(binding.root, "Kalibrasi disimpan", Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveCalibration() {
        val deviceName = binding.etDeviceName.text.toString().trim()
        val wheelDiameterText = binding.etWheelDiameter.text.toString().trim()
        val pulsesText = binding.etPulsesPerRotation.text.toString().trim()

        if (deviceName.isEmpty()) {
            binding.tilDeviceName.error = "Nama device harus diisi"
            return
        }

        if (wheelDiameterText.isEmpty()) {
            binding.tilWheelDiameter.error = "Diameter roda harus diisi"
            return
        }

        if (pulsesText.isEmpty()) {
            binding.tilPulsesPerRotation.error = "Pulses per rotation harus diisi"
            return
        }

        val wheelDiameter = wheelDiameterText.toFloatOrNull()
        val pulsesPerRotation = pulsesText.toIntOrNull()

        if (wheelDiameter == null || wheelDiameter <= 0) {
            binding.tilWheelDiameter.error = "Diameter tidak valid"
            return
        }

        if (pulsesPerRotation == null || pulsesPerRotation <= 0) {
            binding.tilPulsesPerRotation.error = "Pulses tidak valid"
            return
        }

        val unit = if (binding.radioCm.isChecked) "cm" else "inch"
        val vehicleType = binding.etVehicleType.text.toString().trim().ifEmpty { null }
        val tirePressure = binding.etTirePressure.text.toString().trim().toFloatOrNull()
        val loadWeight = binding.etLoadWeight.text.toString().trim().toFloatOrNull()
        val notes = binding.etNotes.text.toString().trim().ifEmpty { null }

        viewModel.saveCalibration(
            deviceName = deviceName,
            wheelDiameter = wheelDiameter,
            wheelDiameterUnit = unit,
            pulsesPerRotation = pulsesPerRotation,
            vehicleType = vehicleType,
            tirePressure = tirePressure,
            loadWeight = loadWeight,
            notes = notes
        )
    }

    private fun testCalibration() {
        val wheelDiameterText = binding.etWheelDiameter.text.toString().trim()
        val pulsesText = binding.etPulsesPerRotation.text.toString().trim()

        if (wheelDiameterText.isEmpty() || pulsesText.isEmpty()) {
            Snackbar.make(binding.root, "Isi diameter dan pulses terlebih dahulu", Snackbar.LENGTH_SHORT).show()
            return
        }

        val wheelDiameter = wheelDiameterText.toFloat()
        val pulsesPerRotation = pulsesText.toInt()
        val unit = if (binding.radioCm.isChecked) "cm" else "inch"

        val summary = viewModel.getCalibrationSummary(wheelDiameter, unit, pulsesPerRotation)

        val message = """
            📏 Hasil Kalibrasi:
            
            Diameter: ${summary["diameter"]}
            Keliling: ${summary["circumference"]}
            Pulses/rotasi: ${summary["pulses_per_rotation"]}
            Jarak/pulse: ${summary["distance_per_pulse"]}
            
            Setiap 1000 pulses = ${summary["distance_per_1000_pulses"]}
        """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Test Kalibrasi")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}